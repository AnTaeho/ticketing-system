package com.example.ticketing.booking.hold;

import com.example.ticketing.booking.BookingProperties;
import com.example.ticketing.booking.common.BookingErrorCode;
import com.example.ticketing.booking.common.BookingException;
import com.example.ticketing.booking.common.DbClock;
import com.example.ticketing.booking.common.ThrottledWarn;
import com.example.ticketing.booking.hold.SeatHoldTransaction.PersistedHold;
import com.example.ticketing.booking.hold.SeatHoldTransaction.ReleasedHold;
import com.example.ticketing.booking.hold.domain.SeatHold;
import com.example.ticketing.booking.hold.dto.ActiveHold;
import com.example.ticketing.booking.hold.dto.HoldCreated;
import com.example.ticketing.booking.hold.dto.HoldDetail;
import com.example.ticketing.booking.hold.dto.HoldSeatView;
import com.example.ticketing.booking.hold.gate.GateResult;
import com.example.ticketing.booking.hold.gate.RedisSeatGate;
import com.example.ticketing.booking.payment.BookingPaymentRepository;
import com.example.ticketing.booking.queue.BookingQueueService;
import com.example.ticketing.booking.seat.SeatCatalog;
import com.example.ticketing.booking.seat.SeatCatalog.PerformanceInfo;
import com.example.ticketing.booking.seat.SeatCatalog.SeatSelection;
import com.example.ticketing.booking.seat.SeatRepository;
import com.example.ticketing.booking.show.domain.PerformanceState;
import io.lettuce.core.RedisException;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

// 트랜잭션을 열지 않는다. 검증·게이트 동안 DB 커넥션을 잡지 않으려고
@Slf4j
@Service
public class SeatHoldService {

    private static final String DB_COUNTER = "booking.hold.db";
    private static final String GATE_COUNTER = "booking.hold.gate";
    private static final String FALLBACK_COUNTER = "booking.hold.fallback";
    private static final String REFLECT_FAILURE = "booking.redis.reflect.failure";

    private final SeatCatalog seatCatalog;
    private final SeatHoldTransaction seatHoldTransaction;
    private final SeatHoldRepository seatHoldRepository;
    private final SeatRepository seatRepository;
    private final BookingPaymentRepository paymentRepository;
    private final DbClock dbClock;
    private final RedisSeatGate gate;
    private final BookingQueueService queueService;
    private final boolean gateEnabled;
    private final boolean queueRequired;
    private final Duration holdTtl;
    private final Counter dbOk;
    private final Counter dbStateChanged;
    private final Counter dbUserHasHold;
    private final Counter gateOk;
    private final Counter gateSeatTaken;
    private final Counter gateUserHasHold;
    private final Counter gateCooldown;
    private final Counter gateSkipped;
    private final Counter gateError;
    private final Counter fallbackAcquired;
    private final Counter fallbackRejected;
    private final Counter queueConsumeFailure;
    private final Semaphore fallbackPermits;
    private final ThrottledWarn redisWarn = new ThrottledWarn(log);

    public SeatHoldService(SeatCatalog seatCatalog, SeatHoldTransaction seatHoldTransaction,
                           SeatHoldRepository seatHoldRepository, SeatRepository seatRepository,
                           BookingPaymentRepository paymentRepository, DbClock dbClock, RedisSeatGate gate,
                           BookingQueueService queueService, BookingProperties properties,
                           MeterRegistry meterRegistry) {
        this.seatCatalog = seatCatalog;
        this.seatHoldTransaction = seatHoldTransaction;
        this.seatHoldRepository = seatHoldRepository;
        this.seatRepository = seatRepository;
        this.paymentRepository = paymentRepository;
        this.dbClock = dbClock;
        this.gate = gate;
        this.queueService = queueService;
        this.gateEnabled = properties.gate().enabled();
        this.queueRequired = properties.queue().required();
        this.holdTtl = properties.hold().ttl();
        this.dbOk = meterRegistry.counter(DB_COUNTER, "result", "ok");
        this.dbStateChanged = meterRegistry.counter(DB_COUNTER, "result", "state_changed");
        this.dbUserHasHold = meterRegistry.counter(DB_COUNTER, "result", "user_has_hold");
        this.gateOk = meterRegistry.counter(GATE_COUNTER, "result", "ok");
        this.gateSeatTaken = meterRegistry.counter(GATE_COUNTER, "result", "seat_taken");
        this.gateUserHasHold = meterRegistry.counter(GATE_COUNTER, "result", "user_has_hold");
        this.gateCooldown = meterRegistry.counter(GATE_COUNTER, "result", "cooldown");
        this.gateSkipped = meterRegistry.counter(GATE_COUNTER, "result", "skipped");
        this.gateError = meterRegistry.counter(GATE_COUNTER, "result", "error");
        this.fallbackAcquired = meterRegistry.counter(FALLBACK_COUNTER, "result", "acquired");
        this.fallbackRejected = meterRegistry.counter(FALLBACK_COUNTER, "result", "rejected");
        this.fallbackPermits = new Semaphore(properties.gate().fallbackMaxConcurrency());
        this.queueConsumeFailure = meterRegistry.counter(REFLECT_FAILURE, "op", "queue_consume");
    }

    // 토큰은 커밋까지 성공했을 때만 소모한다. 게이트·DB에서 진 사용자는 같은 토큰으로 다른 좌석을 고른다
    public HoldCreated hold(long userId, long performanceId, List<Long> seatIds, String queueToken) {
        SeatSelection selection = validate(performanceId, seatIds);
        String holdToken = UUID.randomUUID().toString();
        if (!ensureAdmitted(userId, performanceId, queueToken)) {
            // Redis가 죽은 걸 이미 봤으니 게이트는 부르지 않는다
            (gateEnabled ? gateError : gateSkipped).increment();
            return holdWithoutRedis(userId, selection, holdToken, queueToken, false);
        }
        if (!passGate(userId, selection, holdToken)) {
            return holdWithoutRedis(userId, selection, holdToken, queueToken, true);
        }
        return persist(userId, selection, holdToken, queueToken, gateEnabled);
    }

    public HoldDetail getHold(long userId, long holdId) {
        SeatHold hold = seatHoldRepository.findByIdAndUserId(holdId, userId)
                .orElseThrow(() -> new BookingException(BookingErrorCode.HOLD_NOT_FOUND, Map.of("holdId", holdId)));
        List<HoldSeatView> seats = seatRepository.findByPerformanceIdAndHoldIdOrderByIdAsc(hold.getPerformanceId(), holdId).stream()
                .map(HoldSeatView::of)
                .toList();
        Long paymentId = paymentRepository.findIdByHoldId(holdId).orElse(null);
        return new HoldDetail(hold.getId(), hold.getPerformanceId(), hold.getStatus(), hold.getExpiresAt(), hold.getPayDeadline(),
                dbClock.now(), seats, hold.getTotalAmount(), paymentId);
    }

    // 이미 잡아 둔 자리가 있다는 응답을 받은 화면이 결제 화면으로 이어 가려고 쓴다
    public ActiveHold getActiveHold(long userId, long performanceId) {
        SeatHold hold = seatHoldRepository.findActive(userId, performanceId)
                .orElseThrow(() -> new BookingException(BookingErrorCode.HOLD_NOT_FOUND,
                        Map.of("performanceId", performanceId)));
        return new ActiveHold(hold.getId(), hold.getStatus(), hold.getExpiresAt());
    }

    // 커밋이 끝난 뒤 게이트 키를 지운다
    public void release(long userId, long holdId) {
        ReleasedHold released = seatHoldTransaction.releaseHold(userId, holdId);
        if (gateEnabled) {
            gate.releaseQuietly(released.performanceId(), released.userId(), released.seatIds(),
                    released.holdToken());
        }
    }

    // 대기열과 같은 DB 추정 시각으로 거르는 사전 확인이다. 경계 판정은 트랜잭션 안에서 DB 시각으로 다시 한다
    private SeatSelection validate(long performanceId, List<Long> seatIds) {
        PerformanceInfo performance = seatCatalog.performance(performanceId);
        if (PerformanceState.of(performance.openAt(), performance.startsAt(), dbClock.estimatedNow())
                != PerformanceState.OPEN) {
            throw new BookingException(BookingErrorCode.NOT_OPEN, Map.of("performanceId", performanceId));
        }
        return seatCatalog.validateSeats(performanceId, seatIds);
    }

    // 게이트 스위치와 별개다. 처리열을 끄는 것은 booking.queue.required뿐이다. false면 Redis 장애로 확인을 건너뛴 것이다
    private boolean ensureAdmitted(long userId, long performanceId, String queueToken) {
        if (!queueRequired) {
            return true;
        }
        if (queueToken == null || queueToken.isBlank()) {
            throw new BookingException(BookingErrorCode.NOT_ADMITTED);
        }
        boolean admitted;
        try {
            admitted = queueService.isAdmitted(performanceId, queueToken, userId);
        } catch (DataAccessException | RedisException e) {
            warnRedisFailure("queue_check", performanceId, e);
            return false;
        }
        if (!admitted) {
            throw new BookingException(BookingErrorCode.NOT_ADMITTED);
        }
        return true;
    }

    // 선점은 이미 커밋됐으므로 실패해도 응답을 바꾸지 않는다. 남은 토큰은 처리열 만료로 빠진다
    private void consumeQuietly(long userId, long performanceId, String queueToken) {
        if (!queueRequired) {
            return;
        }
        try {
            queueService.consume(performanceId, queueToken, userId);
        } catch (DataAccessException | RedisException e) {
            queueConsumeFailure.increment();
            warnRedisFailure("queue_consume", performanceId, e);
        }
    }

    // 게이트 OFF면 Redis를 부르지 않는다. 거절이면 DB에 닿지 않고 409로 끝난다. false면 Redis 장애로 판정을 못 한 것이다
    private boolean passGate(long userId, SeatSelection selection, String holdToken) {
        if (!gateEnabled) {
            gateSkipped.increment();
            return true;
        }
        GateResult result;
        try {
            result = gate.tryHold(selection.performanceId(), userId, selection.seatIds(), holdToken, holdTtl);
        } catch (DataAccessException | RedisException e) {
            gateError.increment();
            warnRedisFailure("gate", selection.performanceId(), e);
            return false;
        }
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
        return true;
    }

    // Redis 없이 DB가 판정한다(1인 1선점도 DB가 지키고 쿨다운은 꺼진다). 동시 수는 세마포어로 묶고 자리가 없으면 기다리지 않는다
    private HoldCreated holdWithoutRedis(long userId, SeatSelection selection, String holdToken, String queueToken,
                                         boolean gateTried) {
        if (!fallbackPermits.tryAcquire()) {
            fallbackRejected.increment();
            throw new BookingException(BookingErrorCode.BUSY, Map.of("performanceId", selection.performanceId()));
        }
        fallbackAcquired.increment();
        try {
            // 시간 초과로 끊긴 hold.lua가 실제로는 키를 썼을 수 있어 게이트를 불렀을 때만 실패 시 지운다
            return persist(userId, selection, holdToken, queueToken, gateTried);
        } finally {
            fallbackPermits.release();
        }
    }

    private HoldCreated persist(long userId, SeatSelection selection, String holdToken, String queueToken,
                                boolean releaseGateOnFailure) {
        PersistedHold persisted;
        try {
            persisted = decideInDb(userId, selection, holdToken);
        } catch (Throwable e) {
            // 게이트에 방금 쓴 키를 지운다. DB가 거절했든 DB 예외든 같다
            if (releaseGateOnFailure) {
                gate.releaseQuietly(selection.performanceId(), userId, selection.seatIds(), holdToken);
            }
            throw e;
        }
        consumeQuietly(userId, selection.performanceId(), queueToken);
        return new HoldCreated(persisted.holdId(), persisted.expiresAt(),
                selection.seats().stream().map(HoldSeatView::of).toList(), selection.totalAmount());
    }

    private void warnRedisFailure(String op, long performanceId, RuntimeException e) {
        redisWarn.warn("[booking hold] Redis 실패 op={}, performanceId={}: {}", op, performanceId, e.toString());
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
