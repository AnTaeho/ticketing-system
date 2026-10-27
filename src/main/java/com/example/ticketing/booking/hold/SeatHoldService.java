package com.example.ticketing.booking.hold;

import com.example.ticketing.booking.common.BookingErrorCode;
import com.example.ticketing.booking.common.BookingException;
import com.example.ticketing.booking.common.DbClock;
import com.example.ticketing.booking.hold.SeatHoldTransaction.PersistedHold;
import com.example.ticketing.booking.hold.domain.SeatHold;
import com.example.ticketing.booking.hold.dto.HoldCreated;
import com.example.ticketing.booking.hold.dto.HoldDetail;
import com.example.ticketing.booking.hold.dto.HoldSeatView;
import com.example.ticketing.booking.seat.SeatCatalog;
import com.example.ticketing.booking.seat.SeatCatalog.PerformanceInfo;
import com.example.ticketing.booking.seat.SeatCatalog.SeatSelection;
import com.example.ticketing.booking.seat.SeatRepository;
import com.example.ticketing.booking.show.domain.PerformanceState;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;

// 트랜잭션을 열지 않는다. 검증·게이트 동안 DB 커넥션을 잡지 않으려고
@Service
public class SeatHoldService {

    private static final String DB_COUNTER = "booking.hold.db";

    private final SeatCatalog seatCatalog;
    private final SeatHoldTransaction seatHoldTransaction;
    private final SeatHoldRepository seatHoldRepository;
    private final SeatRepository seatRepository;
    private final DbClock dbClock;
    private final Counter dbOk;
    private final Counter dbStateChanged;
    private final Counter dbUserHasHold;

    public SeatHoldService(SeatCatalog seatCatalog, SeatHoldTransaction seatHoldTransaction,
                           SeatHoldRepository seatHoldRepository, SeatRepository seatRepository,
                           DbClock dbClock, MeterRegistry meterRegistry) {
        this.seatCatalog = seatCatalog;
        this.seatHoldTransaction = seatHoldTransaction;
        this.seatHoldRepository = seatHoldRepository;
        this.seatRepository = seatRepository;
        this.dbClock = dbClock;
        this.dbOk = meterRegistry.counter(DB_COUNTER, "result", "ok");
        this.dbStateChanged = meterRegistry.counter(DB_COUNTER, "result", "state_changed");
        this.dbUserHasHold = meterRegistry.counter(DB_COUNTER, "result", "user_has_hold");
    }

    // queueToken은 처리열 확인(S6.2)에서 쓴다
    public HoldCreated hold(long userId, long performanceId, List<Long> seatIds, String queueToken) {
        SeatSelection selection = validate(performanceId, seatIds);
        String holdToken = UUID.randomUUID().toString();
        PersistedHold persisted = decideInDb(userId, selection, holdToken);
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

    public void release(long userId, long holdId) {
        seatHoldTransaction.releaseHold(userId, holdId);
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
