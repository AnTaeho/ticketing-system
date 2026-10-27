package com.example.ticketing.booking.hold;

import com.example.ticketing.booking.BookingProperties;
import com.example.ticketing.booking.common.BookingErrorCode;
import com.example.ticketing.booking.common.BookingException;
import com.example.ticketing.booking.common.DbClock;
import com.example.ticketing.booking.hold.SeatHoldTransaction.PersistedHold;
import com.example.ticketing.booking.hold.SeatHoldTransaction.ReleasedHold;
import com.example.ticketing.booking.hold.domain.SeatHold;
import com.example.ticketing.booking.hold.dto.HoldCreated;
import com.example.ticketing.booking.hold.dto.HoldDetail;
import com.example.ticketing.booking.hold.dto.HoldSeatView;
import com.example.ticketing.booking.hold.gate.GateResult;
import com.example.ticketing.booking.hold.gate.RedisSeatGate;
import com.example.ticketing.booking.seat.SeatCatalog;
import com.example.ticketing.booking.seat.SeatCatalog.PerformanceInfo;
import com.example.ticketing.booking.seat.SeatCatalog.SeatSelection;
import com.example.ticketing.booking.seat.SeatRepository;
import com.example.ticketing.booking.show.domain.PerformanceState;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;

// 트랜잭션을 열지 않는다. 검증·게이트 동안 DB 커넥션을 잡지 않으려고
@Service
public class SeatHoldService {

    private static final String DB_COUNTER = "booking.hold.db";
    private static final String GATE_COUNTER = "booking.hold.gate";

    private final SeatCatalog seatCatalog;
    private final SeatHoldTransaction seatHoldTransaction;
    private final SeatHoldRepository seatHoldRepository;
    private final SeatRepository seatRepository;
    private final DbClock dbClock;
    private final RedisSeatGate gate;
    private final boolean gateEnabled;
    private final Duration holdTtl;
    private final Counter dbOk;
    private final Counter dbStateChanged;
    private final Counter dbUserHasHold;
    private final Counter gateOk;
    private final Counter gateSeatTaken;
    private final Counter gateUserHasHold;
    private final Counter gateCooldown;
    private final Counter gateSkipped;

    public SeatHoldService(SeatCatalog seatCatalog, SeatHoldTransaction seatHoldTransaction,
                           SeatHoldRepository seatHoldRepository, SeatRepository seatRepository,
                           DbClock dbClock, RedisSeatGate gate, BookingProperties properties,
                           MeterRegistry meterRegistry) {
        this.seatCatalog = seatCatalog;
        this.seatHoldTransaction = seatHoldTransaction;
        this.seatHoldRepository = seatHoldRepository;
        this.seatRepository = seatRepository;
        this.dbClock = dbClock;
        this.gate = gate;
        this.gateEnabled = properties.gate().enabled();
        this.holdTtl = properties.hold().ttl();
        this.dbOk = meterRegistry.counter(DB_COUNTER, "result", "ok");
        this.dbStateChanged = meterRegistry.counter(DB_COUNTER, "result", "state_changed");
        this.dbUserHasHold = meterRegistry.counter(DB_COUNTER, "result", "user_has_hold");
        this.gateOk = meterRegistry.counter(GATE_COUNTER, "result", "ok");
        this.gateSeatTaken = meterRegistry.counter(GATE_COUNTER, "result", "seat_taken");
        this.gateUserHasHold = meterRegistry.counter(GATE_COUNTER, "result", "user_has_hold");
        this.gateCooldown = meterRegistry.counter(GATE_COUNTER, "result", "cooldown");
        this.gateSkipped = meterRegistry.counter(GATE_COUNTER, "result", "skipped");
    }

    // queueToken은 처리열 확인(S6.2)에서 쓴다
    public HoldCreated hold(long userId, long performanceId, List<Long> seatIds, String queueToken) {
        SeatSelection selection = validate(performanceId, seatIds);
        String holdToken = UUID.randomUUID().toString();
        passGate(userId, selection, holdToken);
        PersistedHold persisted;
        try {
            persisted = decideInDb(userId, selection, holdToken);
        } catch (Throwable e) {
            // 게이트에 방금 쓴 키를 지운다. DB가 거절했든 DB 예외든 같다
            if (gateEnabled) {
                gate.releaseQuietly(performanceId, userId, selection.seatIds(), holdToken);
            }
            throw e;
        }
        return new HoldCreated(persisted.holdId(), persisted.expiresAt(),
                selection.seats().stream().map(HoldSeatView::of).toList(), selection.totalAmount());
    }

    public HoldDetail getHold(long userId, long holdId) {
        SeatHold hold = seatHoldRepository.findByIdAndUserId(holdId, userId)
                .orElseThrow(() -> new BookingException(BookingErrorCode.HOLD_NOT_FOUND, Map.of("holdId", holdId)));
        List<HoldSeatView> seats = seatRepository.findByPerformanceIdAndHoldIdOrderByIdAsc(hold.getPerformanceId(), holdId).stream()
                .map(HoldSeatView::of)
                .toList();
        return new HoldDetail(hold.getId(), hold.getStatus(), hold.getExpiresAt(), hold.getPayDeadline(),
                dbClock.now(), seats, hold.getTotalAmount(), null);
    }

    // 커밋이 끝난 뒤 게이트 키를 지운다
    public void release(long userId, long holdId) {
        ReleasedHold released = seatHoldTransaction.releaseHold(userId, holdId);
        if (gateEnabled) {
            gate.releaseQuietly(released.performanceId(), released.userId(), released.seatIds(),
                    released.holdToken());
        }
    }

    // 서버 시계로 거르는 사전 확인이다. 경계 판정은 트랜잭션 안에서 DB 시각으로 다시 한다
    private SeatSelection validate(long performanceId, List<Long> seatIds) {
        PerformanceInfo performance = seatCatalog.performance(performanceId);
        if (PerformanceState.of(performance.openAt(), performance.startsAt(), LocalDateTime.now())
                != PerformanceState.OPEN) {
            throw new BookingException(BookingErrorCode.NOT_OPEN, Map.of("performanceId", performanceId));
        }
        return seatCatalog.validateSeats(performanceId, seatIds);
    }

    // 게이트 OFF면 Redis를 부르지 않는다. 거절이면 DB에 닿지 않고 409로 끝난다
    private void passGate(long userId, SeatSelection selection, String holdToken) {
        if (!gateEnabled) {
            gateSkipped.increment();
            return;
        }
        GateResult result = gate.tryHold(selection.performanceId(), userId, selection.seatIds(), holdToken, holdTtl);
        switch (result.type()) {
            case OK -> gateOk.increment();
            case USER_HAS_HOLD -> {
                gateUserHasHold.increment();
                throw new BookingException(BookingErrorCode.USER_HAS_HOLD);
            }
            case SEAT_TAKEN -> {
                gateSeatTaken.increment();
                throw new BookingException(BookingErrorCode.SEAT_TAKEN, Map.of("seatId", result.seatId()));
            }
            case COOLDOWN -> {
                gateCooldown.increment();
                throw new BookingException(BookingErrorCode.COOLDOWN, Map.of("seatId", result.seatId()));
            }
        }
    }

    private PersistedHold decideInDb(long userId, SeatSelection selection, String holdToken) {
        try {
            PersistedHold persisted = seatHoldTransaction.persistHold(userId, selection, holdToken);
            dbOk.increment();
            return persisted;
        } catch (BookingException e) {
            if (e.getCode() == BookingErrorCode.SEAT_STATE_CHANGED) {
                dbStateChanged.increment();
            } else if (e.getCode() == BookingErrorCode.USER_HAS_HOLD) {
                dbUserHasHold.increment();
            }
            throw e;
        }
    }
}
