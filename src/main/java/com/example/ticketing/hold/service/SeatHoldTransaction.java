package com.example.ticketing.hold.service;

import com.example.ticketing.common.config.TicketingProperties;
import com.example.ticketing.common.error.ApiException;
import com.example.ticketing.common.error.ErrorCode;
import com.example.ticketing.common.time.DbClock;
import com.example.ticketing.hold.domain.SeatHold;
import com.example.ticketing.hold.repository.SeatHoldRepository;
import com.example.ticketing.seat.repository.SeatRepository;
import com.example.ticketing.seat.service.SeatCatalog;
import com.example.ticketing.seat.service.SeatCatalog.PerformanceInfo;
import com.example.ticketing.seat.service.SeatCatalog.SeatSelection;
import com.example.ticketing.show.domain.PerformanceState;
import com.example.ticketing.user.repository.AppUserRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

// 락 순서: app_user → seat_hold → seat(id 오름차순). 이 안에서는 Redis를 부르지 않는다
@Component
@RequiredArgsConstructor
public class SeatHoldTransaction {

    private final AppUserRepository appUserRepository;
    private final SeatHoldRepository seatHoldRepository;
    private final SeatRepository seatRepository;
    private final SeatCatalog seatCatalog;
    private final DbClock dbClock;
    private final TicketingProperties properties;

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public PersistedHold persistHold(long userId, SeatSelection selection, String holdToken) {
        long performanceId = selection.performanceId();
        if (appUserRepository.lockById(userId).isEmpty()) {
            throw new ApiException(ErrorCode.LOGIN_REQUIRED);
        }
        if (seatHoldRepository.countActive(userId, performanceId) > 0) {
            throw new ApiException(ErrorCode.USER_HAS_HOLD);
        }

        LocalDateTime now = dbClock.now();
        // 서비스의 사전 확인은 서버 시계라 오픈 경계에서는 DB 시각으로 다시 판정한다
        PerformanceInfo performance = seatCatalog.performance(performanceId);
        if (PerformanceState.of(performance.openAt(), performance.startsAt(), now) != PerformanceState.OPEN) {
            throw new ApiException(ErrorCode.NOT_OPEN, Map.of("performanceId", performanceId));
        }
        LocalDateTime expiresAt = now.plus(properties.hold().ttl());

        SeatHold hold = seatHoldRepository.save(SeatHold.held(performanceId, userId, holdToken,
                selection.seatIds().size(), selection.totalAmount(), expiresAt));
        int updated = seatRepository.holdSeats(performanceId, selection.seatIds(), hold.getId(), expiresAt);
        if (updated != selection.seatIds().size()) {
            throw new ApiException(ErrorCode.SEAT_STATE_CHANGED, Map.of("seatIds", selection.seatIds()));
        }
        return new PersistedHold(hold.getId(), holdToken, expiresAt);
    }

    // 게이트 키를 지울 좌석 ID는 좌석을 풀기 전에 읽어 둔다
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public ReleasedHold releaseHold(long userId, long holdId) {
        SeatHold hold = seatHoldRepository.lockByIdAndUserId(holdId, userId)
                .orElseThrow(() -> new ApiException(ErrorCode.HOLD_NOT_FOUND, Map.of("holdId", holdId)));
        if (seatHoldRepository.markReleased(holdId) == 0) {
            throw new ApiException(ErrorCode.HOLD_NOT_CANCELLABLE,
                    Map.of("holdId", holdId, "status", hold.getStatus().name()));
        }
        List<Long> seatIds = seatRepository.findHeldSeatIds(holdId);
        seatRepository.releaseHeldSeats(holdId);
        return new ReleasedHold(holdId, hold.getPerformanceId(), userId, hold.getHoldToken(), List.copyOf(seatIds));
    }

    public record PersistedHold(long holdId, String holdToken, LocalDateTime expiresAt) {
    }

    public record ReleasedHold(long holdId, long performanceId, long userId, String holdToken, List<Long> seatIds) {
    }
}
