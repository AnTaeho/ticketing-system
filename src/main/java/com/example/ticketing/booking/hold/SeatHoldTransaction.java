package com.example.ticketing.booking.hold;

import com.example.ticketing.booking.BookingProperties;
import com.example.ticketing.booking.common.BookingErrorCode;
import com.example.ticketing.booking.common.BookingException;
import com.example.ticketing.booking.common.DbClock;
import com.example.ticketing.booking.hold.domain.SeatHold;
import com.example.ticketing.booking.seat.SeatCatalog;
import com.example.ticketing.booking.seat.SeatCatalog.PerformanceInfo;
import com.example.ticketing.booking.seat.SeatCatalog.SeatSelection;
import com.example.ticketing.booking.seat.SeatRepository;
import com.example.ticketing.booking.show.domain.PerformanceState;
import com.example.ticketing.booking.user.AppUserRepository;
import java.time.LocalDateTime;
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
    private final BookingProperties properties;

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public PersistedHold persistHold(long userId, SeatSelection selection, String holdToken) {
        long performanceId = selection.performanceId();
        if (appUserRepository.lockById(userId).isEmpty()) {
            throw new BookingException(BookingErrorCode.LOGIN_REQUIRED);
        }
        if (seatHoldRepository.countActive(userId, performanceId) > 0) {
            throw new BookingException(BookingErrorCode.USER_HAS_HOLD);
        }

        LocalDateTime now = dbClock.now();
        // 서비스의 사전 확인은 서버 시계라 오픈 경계에서는 DB 시각으로 다시 판정한다
        PerformanceInfo performance = seatCatalog.performance(performanceId);
        if (PerformanceState.of(performance.openAt(), performance.startsAt(), now) != PerformanceState.OPEN) {
            throw new BookingException(BookingErrorCode.NOT_OPEN, Map.of("performanceId", performanceId));
        }
        LocalDateTime expiresAt = now.plus(properties.hold().ttl());

        SeatHold hold = seatHoldRepository.save(SeatHold.held(performanceId, userId, holdToken,
                selection.seatIds().size(), selection.totalAmount(), expiresAt));
        int updated = seatRepository.holdSeats(performanceId, selection.seatIds(), hold.getId(), expiresAt);
        if (updated != selection.seatIds().size()) {
            throw new BookingException(BookingErrorCode.SEAT_STATE_CHANGED, Map.of("seatIds", selection.seatIds()));
        }
        return new PersistedHold(hold.getId(), holdToken, expiresAt);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void releaseHold(long userId, long holdId) {
        SeatHold hold = seatHoldRepository.lockByIdAndUserId(holdId, userId)
                .orElseThrow(() -> new BookingException(BookingErrorCode.HOLD_NOT_FOUND, Map.of("holdId", holdId)));
        if (seatHoldRepository.markReleased(holdId) == 0) {
            throw new BookingException(BookingErrorCode.HOLD_NOT_CANCELLABLE,
                    Map.of("holdId", holdId, "status", hold.getStatus().name()));
        }
        seatRepository.releaseHeldSeats(holdId);
    }

    public record PersistedHold(long holdId, String holdToken, LocalDateTime expiresAt) {
    }
}
