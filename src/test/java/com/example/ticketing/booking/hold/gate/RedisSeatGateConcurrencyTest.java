package com.example.ticketing.booking.hold.gate;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.ticketing.booking.support.BookingIntegrationTest;
import com.example.ticketing.booking.support.RedisKeyCleaner;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

@BookingIntegrationTest
class RedisSeatGateConcurrencyTest {

    private static final int THREAD_COUNT = 200;

    @Autowired
    private RedisSeatGate gate;
    @Autowired
    private RedisKeyCleaner cleaner;

    private final long pid = ThreadLocalRandom.current().nextLong(1_000_000_000_000L, 9_000_000_000_000L);

    @AfterEach
    void tearDown() {
        cleaner.clean(pid);
    }

    @Test
    @DisplayName("같은 2석에 200명이 동시에 tryHold하면 OK는 정확히 1, 나머지는 SEAT_TAKEN")
    void sameSeatsTwoHundredUsers() throws InterruptedException {
        List<Long> seats = List.of(101L, 102L);
        ExecutorService executor = Executors.newFixedThreadPool(THREAD_COUNT);
        CountDownLatch ready = new CountDownLatch(THREAD_COUNT);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(THREAD_COUNT);
        Map<GateResult, AtomicInteger> results = new ConcurrentHashMap<>();
        List<Throwable> unexpected = new CopyOnWriteArrayList<>();

        for (int i = 0; i < THREAD_COUNT; i++) {
            long userId = i + 1;
            executor.submit(() -> {
                try {
                    ready.countDown();
                    start.await();
                    GateResult result = gate.tryHold(pid, userId, seats, "t-" + userId, Duration.ofMinutes(5));
                    results.computeIfAbsent(result, r -> new AtomicInteger()).incrementAndGet();
                } catch (Throwable t) {
                    unexpected.add(t);
                } finally {
                    done.countDown();
                }
            });
        }
        assertThat(ready.await(30, TimeUnit.SECONDS)).isTrue();
        start.countDown();
        boolean finished = done.await(30, TimeUnit.SECONDS);
        executor.shutdownNow();
        System.out.printf("[S3.1] 200명 동시 tryHold: %s, 예기치 못한 예외 %d%n", results, unexpected.size());

        assertThat(finished).isTrue();
        unexpected.forEach(Throwable::printStackTrace);
        assertThat(unexpected).isEmpty();
        assertThat(results.get(GateResult.ok()).get()).isEqualTo(1);
        assertThat(results).containsOnlyKeys(GateResult.ok(), GateResult.seatTaken(101L));
        assertThat(results.get(GateResult.seatTaken(101L)).get()).isEqualTo(THREAD_COUNT - 1);
        assertThat(cleaner.keys(pid)).hasSize(3);
    }
}
