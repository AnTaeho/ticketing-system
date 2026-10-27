package com.example.ticketing.booking.support;

import com.example.ticketing.booking.common.DbClock;
import com.example.ticketing.booking.show.ShowAdminService;
import com.example.ticketing.booking.show.dto.NewPerformance;
import com.example.ticketing.booking.user.AppUser;
import com.example.ticketing.booking.user.AppUserRepository;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.test.context.TestComponent;

// 테스트마다 새 공연·회차·사용자를 만든다. 회차는 운영과 같은 관리 서비스 경로로 만든다
@TestComponent
@RequiredArgsConstructor
public class BookingFixtures {

    private final ShowAdminService showAdminService;
    private final AppUserRepository appUserRepository;
    private final DbClock dbClock;

    public LocalDateTime dbNow() {
        return dbClock.now();
    }

    public long newShow() {
        return showAdminService.createShow("테스트 공연 " + shortId(), "테스트 공연장", null);
    }

    // 지금 예매 중인 회차: openAt = DB 시각 - 1분, startsAt = +1일
    public long newPerformance(int sections, int rows, int seatsPerRow) {
        LocalDateTime now = dbClock.now();
        return newPerformance(newShow(), now.minusMinutes(1), now.plusDays(1), sections, rows, seatsPerRow);
    }

    public long newPerformance(long showId, LocalDateTime openAt, LocalDateTime startsAt,
                               int sections, int rows, int seatsPerRow) {
        return showAdminService.createPerformance(
                new NewPerformance(showId, startsAt, openAt, null, sections, rows, seatsPerRow)).performanceId();
    }

    public long newUser() {
        return appUserRepository.save(AppUser.create("fx-" + shortId())).getId();
    }

    private static String shortId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 20);
    }
}
