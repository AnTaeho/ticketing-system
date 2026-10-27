package com.example.ticketing.booking.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.ticketing.booking.BookingProperties;
import com.example.ticketing.booking.support.BookingIntegrationTest;
import java.io.IOException;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;

// 리스 키에는 회차 해시 태그가 없어 RedisKeyCleaner가 지우지 않으므로 여기서 지운다
@BookingIntegrationTest
class SchedulerLeaseTest {

    @Autowired
    private BookingRedis bookingRedis;
    @Autowired
    private BookingProperties props;

    private final String name = "test-" + UUID.randomUUID();
    private final String key = "sched:lease:" + name;

    @AfterEach
    void tearDown() {
        redis().delete(key);
    }

    @Test
    @DisplayName("여러 인스턴스가 동시에 잡으려 하면 하나만 실행하고, 끝나면 리스를 푼다")
    void onlyOneRunsAtATime() throws InterruptedException {
        int instances = 8;
        List<SchedulerLease> leases = new ArrayList<>();
        for (int i = 0; i < instances; i++) {
            leases.add(new SchedulerLease(bookingRedis, "instance-" + i));
        }
        AtomicInteger ran = new AtomicInteger();
        AtomicInteger acquired = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(instances);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(instances);
        for (SchedulerLease lease : leases) {
            pool.submit(() -> {
                try {
                    start.await();
                    if (lease.runIfLeader(name, Duration.ofSeconds(5), () -> {
                        ran.incrementAndGet();
                        sleep(200);
                    })) {
                        acquired.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();
        pool.shutdown();

        assertThat(ran.get()).isEqualTo(1);
        assertThat(acquired.get()).isEqualTo(1);
        assertThat(redis().hasKey(key)).isFalse();
        assertThat(leases.get(0).runIfLeader(name, Duration.ofSeconds(5), () -> { })).isTrue();
    }

    @Test
    @DisplayName("남이 잡은 리스는 건너뛰고 지우지 않는다")
    void skipsAndKeepsOthersLease() {
        SchedulerLease lease = new SchedulerLease(bookingRedis, "mine");
        redis().opsForValue().set(key, "someone-else", Duration.ofSeconds(5));
        AtomicBoolean ran = new AtomicBoolean();

        assertThat(lease.runIfLeader(name, Duration.ofSeconds(5), () -> ran.set(true))).isFalse();

        assertThat(ran).isFalse();
        assertThat(redis().opsForValue().get(key)).isEqualTo("someone-else");
    }

    @Test
    @DisplayName("TTL이 지나면 다른 인스턴스가 잡고, 늦게 끝난 원래 인스턴스는 남의 리스를 지우지 않는다")
    void expiredLeaseIsTakenOverAndNotDeletedByOldOwner() {
        SchedulerLease a = new SchedulerLease(bookingRedis, "a");
        SchedulerLease b = new SchedulerLease(bookingRedis, "b");
        AtomicBoolean bRan = new AtomicBoolean();
        Duration ttl = Duration.ofSeconds(30);

        // 만료는 PTTL로 확인하고, 만료된 뒤의 인계는 키를 지워 흉내 낸다. 실제 시간이 지나기를 기다리지 않는다
        boolean aRan = a.runIfLeader(name, ttl, () -> {
            assertThat(redis().getExpire(key, TimeUnit.MILLISECONDS)).isPositive().isLessThanOrEqualTo(ttl.toMillis());
            assertThat(b.runIfLeader(name, Duration.ofSeconds(5), () -> { })).isFalse();
            redis().delete(key);
            assertThat(b.runIfLeader(name, Duration.ofSeconds(5), () -> bRan.set(true))).isTrue();
            redis().opsForValue().set(key, "c", Duration.ofSeconds(5));
        });

        assertThat(aRan).isTrue();
        assertThat(bRan).isTrue();
        assertThat(redis().opsForValue().get(key)).isEqualTo("c");
    }

    @Test
    @DisplayName("작업이 예외를 내도 리스를 풀고 예외는 그대로 던진다")
    void releasesOnTaskFailure() {
        SchedulerLease lease = new SchedulerLease(bookingRedis, "mine");

        assertThatThrownBy(() -> lease.runIfLeader(name, Duration.ofSeconds(5), () -> {
            throw new IllegalStateException("boom");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(redis().hasKey(key)).isFalse();
    }

    @Test
    @DisplayName("Redis 장애면 예외 없이 그 주기를 건너뛴다")
    void skipsOnRedisFailure() throws IOException {
        BookingRedis dead = TestBookingRedis.connectTo("localhost", closedPort(), props);
        try {
            SchedulerLease lease = new SchedulerLease(dead, "mine");
            AtomicBoolean ran = new AtomicBoolean();
            assertThat(lease.runIfLeader(name, Duration.ofSeconds(5), () -> ran.set(true))).isFalse();
            assertThat(ran).isFalse();
        } finally {
            dead.destroy();
        }
    }

    @Test
    @DisplayName("빈으로 등록된 리스는 기동 때 만든 UUID를 인스턴스 ID로 쓴다")
    void beanInstanceIdIsUuid(@Autowired SchedulerLease bean) {
        assertThat(UUID.fromString(bean.instanceId())).isNotNull();
    }

    private StringRedisTemplate redis() {
        return bookingRedis.template();
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static int closedPort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
