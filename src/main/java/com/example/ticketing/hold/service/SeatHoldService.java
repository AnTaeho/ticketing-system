package com.example.ticketing.hold.service;

import com.example.ticketing.common.config.TicketingProperties;
import com.example.ticketing.common.error.ApiException;
import com.example.ticketing.common.error.ErrorCode;
import com.example.ticketing.common.log.ThrottledWarn;
import com.example.ticketing.common.redis.RedisFailures;
import com.example.ticketing.common.redis.RedisUnavailableException;
import com.example.ticketing.common.time.DbClock;
import com.example.ticketing.hold.domain.GateHold;
import com.example.ticketing.hold.domain.GateResult;
import com.example.ticketing.hold.domain.SeatHold;
import com.example.ticketing.hold.dto.ActiveHoldResult;
import com.example.ticketing.hold.dto.CreateHoldCommand;
import com.example.ticketing.hold.dto.CreateHoldResult;
import com.example.ticketing.hold.dto.HoldDetailResult;
import com.example.ticketing.hold.dto.HoldPlacement;
import com.example.ticketing.hold.repository.SeatGateRedisStore;
import com.example.ticketing.hold.repository.SeatHoldRepository;
import com.example.ticketing.seat.dto.SeatSelection;
import com.example.ticketing.seat.service.SeatCatalog;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import java.util.function.LongSupplier;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

// 트랜잭션을 열지 않는다. 검증·게이트 동안 DB 커넥션을 잡지 않으려고
@Slf4j
@Service
public class SeatHoldService {

    private final SeatCatalog seatCatalog;
    private final SeatHoldTransaction seatHoldTransaction;
    private final SeatHoldRepository seatHoldRepository;
    private final DbClock dbClock;
    private final Clock clock;
    private final SeatGateRedisStore gateStore;
    private final SeatGateReflector gateReflector;
    private final HoldMetrics metrics;
    private final Duration holdTtl;
    private final Semaphore fallbackPermits;
    private final ThrottledWarn redisWarn;

    public SeatHoldService(SeatCatalog seatCatalog, SeatHoldTransaction seatHoldTransaction,
                           SeatHoldRepository seatHoldRepository, DbClock dbClock, Clock clock,
                           SeatGateRedisStore gateStore, SeatGateReflector gateReflector,
                           HoldMetrics metrics, LongSupplier nanoClock,
                           TicketingProperties properties) {
        this.seatCatalog = seatCatalog;
        this.seatHoldTransaction = seatHoldTransaction;
        this.seatHoldRepository = seatHoldRepository;
        this.dbClock = dbClock;
        this.clock = clock;
        this.gateStore = gateStore;
        this.gateReflector = gateReflector;
        this.metrics = metrics;
        this.redisWarn = new ThrottledWarn(log, nanoClock);
        this.holdTtl = properties.hold().ttl();
        this.fallbackPermits = new Semaphore(properties.gate().fallbackMaxConcurrency());
    }

    public CreateHoldResult hold(CreateHoldCommand command) {
        // 게이트를 부르기 전 서버 시계로 거르는 사전 확인이다. 경계 판정은 트랜잭션 안에서 DB 시각으로 다시 한다
        seatCatalog.requireOpen(command.performanceId(), LocalDateTime.now(clock));
        SeatSelection selection = seatCatalog.validateSeats(command.performanceId(), command.seatIds());
        GateHold gateHold = new GateHold(command.userId(), command.performanceId(), UUID.randomUUID().toString(),
                selection.seatIds());
        if (!passGate(gateHold)) {
            return holdWithoutRedis(command, selection, gateHold, true);
        }
        return persist(command, selection, gateHold, gateReflector.enabled());
    }

    public HoldDetailResult getHold(long userId, long holdId) {
        SeatHold hold = seatHoldRepository.findByIdAndUserId(holdId, userId)
                .orElseThrow(() -> new ApiException(ErrorCode.HOLD_NOT_FOUND, Map.of("holdId", holdId)));
        return HoldDetailResult.from(hold,
                seatCatalog.seatsOfHold(hold.getPerformanceId(), holdId),
                seatHoldRepository.findPaymentIdByHoldId(holdId).orElse(null), dbClock.now());
    }

    // 이미 잡아 둔 자리가 있다는 응답을 받은 화면이 결제 화면으로 이어 가려고 쓴다
    public ActiveHoldResult getActiveHold(long userId, long performanceId) {
        return seatHoldRepository.findActive(userId, performanceId)
                .orElseThrow(() -> new ApiException(ErrorCode.HOLD_NOT_FOUND,
                        Map.of("performanceId", performanceId)));
    }

    // 커밋이 끝난 뒤 게이트 키를 지운다
    public void release(long userId, long holdId) {
        gateReflector.release(seatHoldTransaction.heldToReleased(userId, holdId));
    }

    // 게이트 OFF면 Redis를 부르지 않는다. 거절이면 DB에 닿지 않고 409로 끝난다
    private boolean passGate(GateHold gateHold) {
        if (!gateReflector.enabled()) {
            metrics.gateSkipped();
            return true;
        }
        GateResult result;
        try {
            result = gateStore.tryHold(gateHold, holdTtl);
        } catch (RedisUnavailableException e) {
            metrics.gateError();
            warnRedisFailure("gate", gateHold.performanceId(), e);
            return false;
        }
        metrics.gate(result.type());
        switch (result.type()) {
            case OK -> {
            }
            case USER_HAS_HOLD -> throw new ApiException(ErrorCode.USER_HAS_HOLD);
            case SEAT_TAKEN -> throw new ApiException(ErrorCode.SEAT_TAKEN, Map.of("seatId", result.seatId()));
            case COOLDOWN -> throw new ApiException(ErrorCode.COOLDOWN, Map.of("seatId", result.seatId()));
        }
        return true;
    }

    // Redis 없이 DB가 판정한다(1인 1선점도 DB가 지키고 쿨다운은 꺼진다). 동시 수는 세마포어로 묶고 자리가 없으면 기다리지 않는다
    private CreateHoldResult holdWithoutRedis(CreateHoldCommand command, SeatSelection selection, GateHold gateHold,
                                              boolean gateTried) {
        if (!fallbackPermits.tryAcquire()) {
            metrics.fallbackRejected();
            throw new ApiException(ErrorCode.BUSY, Map.of("performanceId", selection.performanceId()));
        }
        metrics.fallbackAcquired();
        try {
            // 시간 초과로 끊긴 hold.lua가 실제로는 키를 썼을 수 있어 게이트를 불렀을 때만 실패 시 지운다
            return persist(command, selection, gateHold, gateTried);
        } finally {
            fallbackPermits.release();
        }
    }

    private CreateHoldResult persist(CreateHoldCommand command, SeatSelection selection, GateHold gateHold,
                                     boolean releaseGateOnFailure) {
        HoldPlacement placement;
        try {
            placement = decideInDb(command.userId(), selection, gateHold.holdToken());
        } catch (RuntimeException e) {
            // 게이트에 방금 쓴 키를 지운다. DB가 거절했든 DB 예외든 같다
            if (releaseGateOnFailure) {
                gateReflector.release(gateHold);
            }
            throw e;
        }
        return new CreateHoldResult(placement.holdId(), placement.expiresAt(),
                selection.seats(), selection.totalAmount());
    }

    private HoldPlacement decideInDb(long userId, SeatSelection selection, String holdToken) {
        try {
            HoldPlacement placement = seatHoldTransaction.persistHold(userId, selection, holdToken);
            metrics.dbAccepted();
            return placement;
        } catch (ApiException e) {
            metrics.dbRejected(e.getCode());
            throw e;
        }
    }

    private void warnRedisFailure(String op, long performanceId, RedisUnavailableException e) {
        redisWarn.warn("[hold] Redis 실패 op={} performanceId={} cause={}", op, performanceId,
                RedisFailures.describe(e));
    }
}
