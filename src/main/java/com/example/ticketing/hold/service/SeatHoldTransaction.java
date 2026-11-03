package com.example.ticketing.hold.service;

import com.example.ticketing.common.config.TicketingProperties;
import com.example.ticketing.common.error.ApiException;
import com.example.ticketing.common.error.ErrorCode;
import com.example.ticketing.common.time.DbClock;
import com.example.ticketing.hold.domain.GateHold;
import com.example.ticketing.hold.domain.SeatHold;
import com.example.ticketing.hold.dto.HoldPlacement;
import com.example.ticketing.hold.repository.SeatHoldRepository;
import com.example.ticketing.seat.dto.SeatSelection;
import com.example.ticketing.seat.repository.SeatRepository;
import com.example.ticketing.seat.service.SeatCatalog;
import com.example.ticketing.user.repository.AppUserRepository;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

// 락 순서: app_user → seat_hold → seat(id 오름차순). 이 안에서는 Redis를 부르지 않는다
@Component
public class SeatHoldTransaction {

    private final AppUserRepository appUserRepository;
    private final SeatHoldRepository seatHoldRepository;
    private final SeatRepository seatRepository;
    private final SeatCatalog seatCatalog;
    private final DbClock dbClock;
    private final Duration holdTtl;

    public SeatHoldTransaction(AppUserRepository appUserRepository, SeatHoldRepository seatHoldRepository,
                               SeatRepository seatRepository, SeatCatalog seatCatalog, DbClock dbClock,
                               TicketingProperties properties) {
        this.appUserRepository = appUserRepository;
        this.seatHoldRepository = seatHoldRepository;
        this.seatRepository = seatRepository;
        this.seatCatalog = seatCatalog;
        this.dbClock = dbClock;
        this.holdTtl = properties.hold().ttl();
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public HoldPlacement persistHold(long userId, SeatSelection selection, String holdToken) {
        long performanceId = selection.performanceId();
        if (appUserRepository.lockById(userId).isEmpty()) {
            throw new ApiException(ErrorCode.LOGIN_REQUIRED);
        }
        if (seatHoldRepository.countActive(userId, performanceId) > 0) {
            throw new ApiException(ErrorCode.USER_HAS_HOLD);
        }

        LocalDateTime now = dbClock.now();
        // 서비스의 사전 확인은 DB 시각 추정값이라 오픈 경계에서 어긋날 수 있어 실제 DB 시각으로 다시 판정한다
        seatCatalog.requireOpen(performanceId, now);
        LocalDateTime expiresAt = now.plus(holdTtl);

        SeatHold hold = seatHoldRepository.save(SeatHold.held(userId, performanceId, holdToken,
                selection.seatIds().size(), selection.totalAmount(), expiresAt));
        int updated = seatRepository.availableOrExpiredToHeld(performanceId, selection.seatIds(), hold.getId(),
                expiresAt);
        if (updated != selection.seatIds().size()) {
            throw new ApiException(ErrorCode.SEAT_STATE_CHANGED, Map.of("seatIds", selection.seatIds()));
        }
        return new HoldPlacement(hold.getId(), expiresAt);
    }

    // 게이트 키를 지울 좌석 ID는 좌석을 풀기 전에 읽어 둔다. 반환은 커밋 뒤 지울 게이트 키다
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public GateHold heldToReleased(long userId, long holdId) {
        SeatHold hold = seatHoldRepository.lockByIdAndUserId(holdId, userId)
                .orElseThrow(() -> new ApiException(ErrorCode.HOLD_NOT_FOUND, Map.of("holdId", holdId)));
        if (seatHoldRepository.heldToReleased(holdId) != 1) {
            throw new ApiException(ErrorCode.HOLD_NOT_CANCELLABLE,
                    Map.of("holdId", holdId, "status", hold.getStatus().name()));
        }
        List<Long> seatIds = seatRepository.findHeldSeatIds(holdId);
        seatRepository.heldToAvailable(holdId);
        return new GateHold(userId, hold.getPerformanceId(), hold.getHoldToken(), seatIds);
    }
}
