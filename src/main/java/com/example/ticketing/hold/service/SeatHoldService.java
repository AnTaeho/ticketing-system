package com.example.ticketing.hold.service;

import com.example.ticketing.common.config.TicketingProperties;
import com.example.ticketing.common.error.ApiException;
import com.example.ticketing.common.error.ErrorCode;
import com.example.ticketing.common.redis.RedisFailures;
import com.example.ticketing.common.redis.RedisUnavailableException;
import com.example.ticketing.hold.domain.GateHold;
import com.example.ticketing.hold.dto.CreateHoldCommand;
import com.example.ticketing.hold.dto.CreateHoldResult;
import com.example.ticketing.hold.dto.HoldPlacement;
import com.example.ticketing.hold.repository.SeatGateRedisStore;
import com.example.ticketing.seat.dto.SeatSelection;
import com.example.ticketing.seat.service.SeatCatalog;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

// 트랜잭션을 열지 않는다. 검증·게이트 동안 DB 커넥션을 잡지 않으려고
@Slf4j
@Service
public class SeatHoldService {

    private final SeatCatalog seatCatalog;
    private final SeatHoldTransaction seatHoldTransaction;
    private final Clock clock;
    private final SeatGateRedisStore gateStore;
    private final SeatGateReflector gateReflector;
    private final Duration holdTtl;

    public SeatHoldService(SeatCatalog seatCatalog, SeatHoldTransaction seatHoldTransaction,
                           Clock clock, SeatGateRedisStore gateStore, SeatGateReflector gateReflector,
                           TicketingProperties properties) {
        this.seatCatalog = seatCatalog;
        this.seatHoldTransaction = seatHoldTransaction;
        this.clock = clock;
        this.gateStore = gateStore;
        this.gateReflector = gateReflector;
        this.holdTtl = properties.hold().ttl();
    }

    public CreateHoldResult hold(CreateHoldCommand command) {
        // 게이트를 부르기 전 서버 시계로 거르는 사전 확인이다. 경계 판정은 트랜잭션 안에서 DB 시각으로 다시 한다
        seatCatalog.requireOpen(command.performanceId(), LocalDateTime.now(clock));
        SeatSelection selection = seatCatalog.validateSeats(command.performanceId(), command.seatIds());
        GateHold gateHold = new GateHold(command.performanceId(), UUID.randomUUID().toString(), selection.seatIds());
        passGate(gateHold);
        HoldPlacement placement;
        try {
            placement = seatHoldTransaction.persistHold(command.userId(), selection, gateHold.holdToken());
        } catch (RuntimeException e) {
            // 게이트에 방금 쓴 키를 지운다. 토큰이 같은 키만 지우므로 남의 키는 건드리지 않는다
            gateReflector.release(gateHold);
            throw e;
        }
        return new CreateHoldResult(placement.holdId(), placement.expiresAt(),
                selection.seats(), selection.totalAmount());
    }

    // 커밋이 끝난 뒤 게이트 키를 지운다
    public void release(long userId, long holdId) {
        gateReflector.release(seatHoldTransaction.heldToReleased(userId, holdId));
    }

    // 게이트가 꺼져 있거나 Redis가 죽었으면 건너뛰고 DB가 판정한다. 이미 잡힌 좌석이면 DB에 닿지 않고 409다
    private void passGate(GateHold gateHold) {
        if (!gateReflector.enabled()) {
            return;
        }
        Optional<Long> takenSeatId;
        try {
            takenSeatId = gateStore.tryHold(gateHold, holdTtl);
        } catch (RedisUnavailableException e) {
            log.warn("[hold] Redis 게이트 실패 performanceId={} cause={}", gateHold.performanceId(),
                    RedisFailures.describe(e));
            return;
        }
        if (takenSeatId.isPresent()) {
            throw new ApiException(ErrorCode.SEAT_TAKEN, Map.of("seatId", takenSeatId.get()));
        }
    }
}
