package com.example.ticketing.support;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.ticketing.common.config.TicketingProperties;
import com.example.ticketing.common.redis.RedisKeys;
import com.example.ticketing.common.redis.TicketingRedis;
import com.example.ticketing.common.time.DbClock;
import com.example.ticketing.queue.domain.QueueTokenStatus;
import com.example.ticketing.queue.dto.QueueEntered;
import com.example.ticketing.queue.service.QueueService;
import com.example.ticketing.show.dto.NewPerformance;
import com.example.ticketing.show.service.ShowAdminService;
import com.example.ticketing.user.domain.AppUser;
import com.example.ticketing.user.repository.AppUserRepository;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.test.context.TestComponent;

// 테스트마다 새 공연·회차·사용자를 만든다. 회차는 운영과 같은 관리 서비스 경로로 만든다
@TestComponent
@RequiredArgsConstructor
public class Fixtures {

    private final ShowAdminService showAdminService;
    private final AppUserRepository appUserRepository;
    private final DbClock dbClock;
    private final QueueService queueService;
    private final TicketingRedis bookingRedis;
    private final TicketingProperties properties;

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

    // 운영과 같은 진입 경로로 처리열 토큰을 받는다. 바로 입장하지 못하면 테스트가 실패한다
    public String admit(long performanceId, long userId) {
        QueueEntered entered = queueService.enter(performanceId, userId);
        assertThat(entered.status())
                .as("회차 %d 사용자 %d가 처리열에 바로 들어가야 한다", performanceId, userId)
                .isEqualTo(QueueTokenStatus.PROCESSING);
        return entered.token();
    }

    // 정원을 넘겨 입장시킨다(동시성 테스트용). 부를 때마다 새 토큰을 처리열에 직접 넣는다
    public String forceAdmit(long performanceId, long userId) {
        String member = userId + "." + UUID.randomUUID();
        long expireAt = System.currentTimeMillis() + properties.queue().processingTokenTtl().toMillis();
        bookingRedis.template().opsForZSet().add(RedisKeys.queueProcessing(performanceId), member, expireAt);
        bookingRedis.template().opsForValue().set(RedisKeys.queueUser(performanceId, userId), member,
                properties.queue().waitingTokenTtl());
        return member;
    }

    private static String shortId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 20);
    }
}
