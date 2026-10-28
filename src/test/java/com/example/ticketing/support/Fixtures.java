package com.example.ticketing.support;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.ticketing.common.config.TicketingProperties;
import com.example.ticketing.common.redis.RedisKeys;
import com.example.ticketing.common.time.DbClock;
import com.example.ticketing.hold.dto.CreateHoldCommand;
import com.example.ticketing.hold.dto.CreateHoldResult;
import com.example.ticketing.hold.service.SeatHoldService;
import com.example.ticketing.payment.dto.PayCommand;
import com.example.ticketing.payment.dto.PaymentOutcome;
import com.example.ticketing.payment.dto.PaymentResult;
import com.example.ticketing.payment.service.PaymentService;
import com.example.ticketing.queue.domain.QueueTokenStatus;
import com.example.ticketing.queue.dto.QueueEnterResult;
import com.example.ticketing.queue.service.QueueService;
import com.example.ticketing.show.dto.CreatePerformanceCommand;
import com.example.ticketing.show.dto.CreateShowCommand;
import com.example.ticketing.show.service.ShowAdminService;
import com.example.ticketing.user.domain.AppUser;
import com.example.ticketing.user.repository.AppUserRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.test.context.TestComponent;

// 테스트 데이터를 운영 경로로 만든다. 만든 회차는 기억했다가 테스트가 끝나면 Redis 키를 지운다(DB 행은 테스트 DB에 남긴다)
@TestComponent
@RequiredArgsConstructor
public class Fixtures {

    private final ShowAdminService showAdminService;
    private final AppUserRepository appUserRepository;
    private final DbClock dbClock;
    private final QueueService queueService;
    private final SeatHoldService seatHoldService;
    private final PaymentService paymentService;
    private final TestDb db;
    private final TestRedis testRedis;
    private final TicketingProperties properties;
    private final Set<Long> performances = ConcurrentHashMap.newKeySet();

    public LocalDateTime dbNow() {
        return dbClock.now();
    }

    public long newShow() {
        return showAdminService.createShow(new CreateShowCommand("테스트 공연 " + shortId(), "테스트 공연장", null))
                .showId();
    }

    // 지금 예매 중인 회차: openAt = DB 시각 - 1분, startsAt = +1일
    public long newPerformance(int sections, int rows, int seatsPerRow) {
        LocalDateTime now = dbClock.now();
        return newPerformance(newShow(), now.minusMinutes(1), now.plusDays(1), sections, rows, seatsPerRow);
    }

    public long newPerformance(long showId, LocalDateTime openAt, LocalDateTime startsAt,
                               int sections, int rows, int seatsPerRow) {
        long pid = showAdminService.createPerformance(
                        new CreatePerformanceCommand(showId, startsAt, openAt, null, sections, rows, seatsPerRow))
                .performanceId();
        performances.add(pid);
        return pid;
    }

    // DB 행 없이 Redis 키만 쓰는 테스트의 회차 ID. 다른 테스트의 회차와 겹치지 않게 큰 수를 고른다
    public long redisOnlyPerformanceId() {
        long pid = ThreadLocalRandom.current().nextLong(1_000_000_000_000L, 9_000_000_000_000L);
        performances.add(pid);
        return pid;
    }

    // 직접 고른 회차 ID의 키도 테스트가 끝나면 지운다
    public long track(long performanceId) {
        performances.add(performanceId);
        return performanceId;
    }

    public long newUser() {
        return appUserRepository.save(AppUser.create("fx-" + shortId())).getId();
    }

    // 운영과 같은 진입 경로로 처리열 토큰을 받는다. 바로 입장하지 못하면 테스트가 실패한다
    public String admit(long performanceId, long userId) {
        QueueEnterResult entered = queueService.enter(userId, performanceId);
        assertThat(entered.status())
                .as("회차 %d 사용자 %d가 처리열에 바로 들어가야 한다", performanceId, userId)
                .isEqualTo(QueueTokenStatus.PROCESSING);
        return entered.queueToken();
    }

    // 정원을 넘겨 입장시킨다(동시성 테스트용). 부를 때마다 새 토큰을 처리열에 직접 넣는다
    public String forceAdmit(long performanceId, long userId) {
        String queueToken = userId + "." + UUID.randomUUID();
        long now = System.currentTimeMillis();
        long expireAt = now + Math.min(properties.queue().processingIdleTimeout().toMillis(),
                properties.queue().processingMaxSession().toMillis());
        testRedis.template().opsForZSet().add(RedisKeys.queueProcessing(performanceId), queueToken, expireAt);
        testRedis.template().opsForZSet().add(RedisKeys.queueAdmitted(performanceId), queueToken, now);
        testRedis.template().opsForValue().set(RedisKeys.queueUser(userId, performanceId), queueToken,
                properties.queue().waitingTokenTtl());
        return queueToken;
    }

    // 새 회차(1구역 1열 6석)에서 새 사용자가 앞에서부터 seatCount석을 잡은 상태
    public Held held(int seatCount) {
        long pid = newPerformance(1, 1, 6);
        return held(pid, db.seatIds(pid).subList(0, seatCount));
    }

    public Held held(long pid, List<Long> seatIds) {
        return held(pid, newUser(), seatIds);
    }

    public Held held(long pid, long userId, List<Long> seatIds) {
        CreateHoldResult hold = seatHoldService.hold(
                new CreateHoldCommand(userId, pid, seatIds, admit(pid, userId)));
        return new Held(pid, userId, hold.holdId(), List.copyOf(seatIds), hold.totalAmount(),
                db.holdToken(hold.holdId()));
    }

    // held에 이어 결제 승인까지 마쳐 예매가 확정된 상태. PG 결과는 테스트가 정한 대역을 따른다
    public Confirmed confirmed(int seatCount) {
        return confirm(held(seatCount));
    }

    public Confirmed confirmed(long pid, List<Long> seatIds) {
        return confirm(held(pid, seatIds));
    }

    public Confirmed confirmed(long pid, long userId, List<Long> seatIds) {
        return confirm(held(pid, userId, seatIds));
    }

    private Confirmed confirm(Held held) {
        PaymentResult paid = paymentService.pay(
                new PayCommand(held.userId(), held.holdId(), UUID.randomUUID().toString(), Optional.empty()));
        assertThat(paid.outcome()).as("hold %d 결제가 확정돼야 한다", held.holdId()).isEqualTo(PaymentOutcome.CONFIRMED);
        return new Confirmed(held.pid(), held.userId(), held.holdId(), paid.paymentId(), paid.bookingId(),
                held.seatIds(), held.totalAmount(), held.holdToken());
    }

    void cleanUp() {
        performances.forEach(testRedis::clean);
        performances.clear();
    }

    private static String shortId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 20);
    }

    public record Held(long pid, long userId, long holdId, List<Long> seatIds, int totalAmount, String holdToken) {
    }

    public record Confirmed(long pid, long userId, long holdId, long paymentId, long bookingId, List<Long> seatIds,
                            int totalAmount, String holdToken) {
    }
}
