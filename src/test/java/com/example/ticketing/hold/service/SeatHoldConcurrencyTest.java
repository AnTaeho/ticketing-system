package com.example.ticketing.hold.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.ticketing.common.config.TicketingProperties;
import com.example.ticketing.common.error.ApiException;
import com.example.ticketing.common.error.ErrorCode;
import com.example.ticketing.hold.dto.HoldCreated;
import com.example.ticketing.support.Fixtures;
import com.example.ticketing.support.IntegrationTest;
import com.example.ticketing.support.RedisKeyCleaner;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;

// 게이트 ON에서 돈다. 같은 테스트를 게이트 OFF로 돌리는 것은 SeatHoldConcurrencyGateOffTest
@IntegrationTest
class SeatHoldConcurrencyTest {

    private static final int THREAD_COUNT = 500;
    private static final int CROSS_ROUNDS = 200;

    @Autowired
    private SeatHoldService seatHoldService;
    @Autowired
    private Fixtures fixtures;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private MeterRegistry meterRegistry;
    @Autowired
    private TicketingProperties properties;
    @Autowired
    private RedisKeyCleaner redisKeyCleaner;

    @Test
    @DisplayName("같은 2석에 500명이 동시에 선점하면 성공 1, 나머지는 409(게이트 ON: SEAT_TAKEN, OFF: SEAT_STATE_CHANGED)")
    void sameSeatsFiveHundredUsers() throws InterruptedException {
        long pid = fixtures.newPerformance(1, 1, 4);
        try {
            sameSeatsFiveHundredUsers(pid);
        } finally {
            redisKeyCleaner.clean(pid);
        }
    }

    private void sameSeatsFiveHundredUsers(long pid) throws InterruptedException {
        List<Long> target = seatIds(pid).subList(0, 2);
        // 정원(200)보다 많은 500명을 처리열에 직접 넣는다. 재려는 것은 좌석 경합이다
        Map<Long, String> tokens = new LinkedHashMap<>();
        for (int i = 0; i < THREAD_COUNT; i++) {
            long userId = fixtures.newUser();
            tokens.put(userId, fixtures.forceAdmit(pid, userId));
        }
        double okBefore = dbCounter("ok");
        double changedBefore = dbCounter("state_changed");
        double dbBefore = dbTotal();
        double gateOkBefore = gateCounter("ok");
        double gateTakenBefore = gateCounter("seat_taken");
        double gateSkippedBefore = gateCounter("skipped");

        ExecutorService executor = Executors.newFixedThreadPool(THREAD_COUNT);
        CountDownLatch ready = new CountDownLatch(THREAD_COUNT);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(THREAD_COUNT);
        AtomicInteger success = new AtomicInteger();
        Map<ErrorCode, AtomicInteger> rejected = new ConcurrentHashMap<>();
        List<Throwable> unexpected = new CopyOnWriteArrayList<>();

        for (Map.Entry<Long, String> entry : tokens.entrySet()) {
            long userId = entry.getKey();
            String token = entry.getValue();
            executor.submit(() -> {
                try {
                    ready.countDown();
                    start.await();
                    seatHoldService.hold(userId, pid, target, token);
                    success.incrementAndGet();
                } catch (ApiException e) {
                    rejected.computeIfAbsent(e.getCode(), c -> new AtomicInteger()).incrementAndGet();
                } catch (Throwable t) {
                    unexpected.add(t);
                } finally {
                    done.countDown();
                }
            });
        }
        assertThat(ready.await(30, TimeUnit.SECONDS)).isTrue();
        long startedAt = System.nanoTime();
        start.countDown();
        boolean finished = done.await(60, TimeUnit.SECONDS);
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
        executor.shutdownNow();
        double dbDelta = dbTotal() - dbBefore;
        System.out.printf("[S3.2] 500명 동시 선점(게이트 %s): %dms, 성공 %d, 거절 %s, booking.hold.db 증가 %.0f, "
                        + "예기치 못한 예외 %d%n",
                gateLabel(), elapsedMs, success.get(), rejected, dbDelta, unexpected.size());

        assertThat(finished).isTrue();
        unexpected.forEach(Throwable::printStackTrace);
        assertThat(unexpected).isEmpty();
        assertThat(success.get()).isEqualTo(1);
        ErrorCode expected = seatConflictCode();
        assertThat(rejected).containsOnlyKeys(expected);
        assertThat(rejected.get(expected).get()).isEqualTo(THREAD_COUNT - 1);

        List<Long> holdIds = jdbcTemplate.queryForList(
                "SELECT id FROM seat_hold WHERE performance_id = ?", Long.class, pid);
        assertThat(holdIds).hasSize(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM seat WHERE performance_id = ? AND status = 'HELD' AND hold_id = ?",
                Long.class, pid, holdIds.get(0))).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM seat WHERE performance_id = ? AND status = 'HELD'",
                Long.class, pid)).isEqualTo(2);
        assertThat(dbCounter("ok") - okBefore).isEqualTo(1);
        if (gateEnabled()) {
            // 게이트가 Redis에서 거절하므로 DB에 닿는 선점은 이긴 1건뿐이다
            assertThat(dbDelta).isEqualTo(1);
            assertThat(dbCounter("state_changed") - changedBefore).isZero();
            assertThat(gateCounter("ok") - gateOkBefore).isEqualTo(1);
            assertThat(gateCounter("seat_taken") - gateTakenBefore).isEqualTo(THREAD_COUNT - 1);
        } else {
            assertThat(dbDelta).isEqualTo(THREAD_COUNT);
            assertThat(dbCounter("state_changed") - changedBefore).isEqualTo(THREAD_COUNT - 1);
            assertThat(gateCounter("skipped") - gateSkippedBefore).isEqualTo(THREAD_COUNT);
            assertThat(redisKeyCleaner.gateKeys(pid)).isEmpty();
        }
    }

    @Test
    @DisplayName("교차 선점 A[1,2] / B[2,1]를 200회 반복해도 데드락 0, 매 회 성공 1")
    void crossOrderNoDeadlock() throws InterruptedException {
        long pid = fixtures.newPerformance(1, 1, 2);
        try {
            crossOrderNoDeadlock(pid);
        } finally {
            redisKeyCleaner.clean(pid);
        }
    }

    private void crossOrderNoDeadlock(long pid) throws InterruptedException {
        ErrorCode conflict = seatConflictCode();
        List<Long> ids = seatIds(pid);
        List<Long> forward = List.of(ids.get(0), ids.get(1));
        List<Long> reverse = List.of(ids.get(1), ids.get(0));
        long userA = fixtures.newUser();
        long userB = fixtures.newUser();

        ExecutorService executor = Executors.newFixedThreadPool(2);
        List<Throwable> unexpected = new CopyOnWriteArrayList<>();
        AtomicInteger deadlocks = new AtomicInteger();
        List<Integer> badRounds = new ArrayList<>();
        long startedAt = System.nanoTime();

        try {
            for (int round = 0; round < CROSS_ROUNDS; round++) {
                CountDownLatch start = new CountDownLatch(1);
                CountDownLatch done = new CountDownLatch(2);
                List<HoldCreated> created = new CopyOnWriteArrayList<>();
                AtomicInteger conflicted = new AtomicInteger();
                // 진 쪽은 토큰이 남아 같은 토큰을 다시 받고, 이긴 쪽은 소모돼 새로 받는다
                for (Object[] attempt : new Object[][]{{userA, forward}, {userB, reverse}}) {
                    long userId = (long) attempt[0];
                    @SuppressWarnings("unchecked")
                    List<Long> seats = (List<Long>) attempt[1];
                    String token = fixtures.admit(pid, userId);
                    executor.submit(() -> {
                        try {
                            start.await();
                            created.add(seatHoldService.hold(userId, pid, seats, token));
                        } catch (ApiException e) {
                            if (e.getCode() == conflict) {
                                conflicted.incrementAndGet();
                            } else {
                                unexpected.add(e);
                            }
                        } catch (Throwable t) {
                            if (isDeadlock(t)) {
                                deadlocks.incrementAndGet();
                            }
                            unexpected.add(t);
                        } finally {
                            done.countDown();
                        }
                    });
                }
                start.countDown();
                assertThat(done.await(60, TimeUnit.SECONDS)).isTrue();
                if (created.size() != 1 || conflicted.get() != 1) {
                    badRounds.add(round);
                }
                // 다음 회차를 위해 이긴 쪽의 선점을 풀어 같은 좌석을 다시 쓴다
                for (HoldCreated hold : created) {
                    long owner = jdbcTemplate.queryForObject(
                            "SELECT user_id FROM seat_hold WHERE id = ?", Long.class, hold.holdId());
                    seatHoldService.release(owner, hold.holdId());
                }
            }
        } finally {
            executor.shutdownNow();
        }
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
        System.out.printf("[S3.2] 교차 선점 %d회(게이트 %s): %dms, 데드락 %d, 성공 1이 아닌 회차 %s, 예기치 못한 예외 %d%n",
                CROSS_ROUNDS, gateLabel(), elapsedMs, deadlocks.get(), badRounds, unexpected.size());

        unexpected.forEach(Throwable::printStackTrace);
        assertThat(deadlocks.get()).isZero();
        assertThat(unexpected).isEmpty();
        assertThat(badRounds).isEmpty();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM seat_hold WHERE performance_id = ? AND status = 'RELEASED'",
                Long.class, pid)).isEqualTo(CROSS_ROUNDS);
        // 매 회 취소가 게이트 키도 지운다. 게이트 OFF면 처음부터 키가 없다
        assertThat(redisKeyCleaner.gateKeys(pid)).isEmpty();
    }

    @Test
    @DisplayName("같은 사용자가 서로 다른 좌석으로 동시에 10번 선점해도 성공 1, 나머지는 USER_HAS_HOLD")
    void sameUserConcurrent() throws InterruptedException {
        long pid = fixtures.newPerformance(1, 1, 10);
        try {
            sameUserConcurrent(pid);
        } finally {
            redisKeyCleaner.clean(pid);
        }
    }

    private void sameUserConcurrent(long pid) throws InterruptedException {
        int attempts = 10;
        List<Long> ids = seatIds(pid);
        long userId = fixtures.newUser();

        ExecutorService executor = Executors.newFixedThreadPool(attempts);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(attempts);
        AtomicInteger success = new AtomicInteger();
        Map<ErrorCode, AtomicInteger> rejected = new ConcurrentHashMap<>();
        List<Throwable> unexpected = new CopyOnWriteArrayList<>();
        // 시도마다 다른 토큰을 넣는다. 한 토큰이면 먼저 이긴 요청의 소모로 늦은 요청이 NOT_ADMITTED가 될 수 있다
        List<String> tokens = new ArrayList<>(attempts);
        for (int i = 0; i < attempts; i++) {
            tokens.add(fixtures.forceAdmit(pid, userId));
        }
        for (int i = 0; i < attempts; i++) {
            long seatId = ids.get(i);
            String token = tokens.get(i);
            executor.submit(() -> {
                try {
                    start.await();
                    seatHoldService.hold(userId, pid, List.of(seatId), token);
                    success.incrementAndGet();
                } catch (ApiException e) {
                    rejected.computeIfAbsent(e.getCode(), c -> new AtomicInteger()).incrementAndGet();
                } catch (Throwable t) {
                    unexpected.add(t);
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertThat(done.await(60, TimeUnit.SECONDS)).isTrue();
        executor.shutdownNow();

        unexpected.forEach(Throwable::printStackTrace);
        assertThat(unexpected).isEmpty();
        assertThat(success.get()).isEqualTo(1);
        assertThat(rejected).containsOnlyKeys(ErrorCode.USER_HAS_HOLD);
        assertThat(rejected.get(ErrorCode.USER_HAS_HOLD).get()).isEqualTo(attempts - 1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM seat_hold WHERE performance_id = ? AND user_id = ?",
                Long.class, pid, userId)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM seat WHERE performance_id = ? AND status = 'HELD'",
                Long.class, pid)).isEqualTo(1);
        if (!gateEnabled()) {
            assertThat(redisKeyCleaner.gateKeys(pid)).isEmpty();
        }
    }

    private boolean gateEnabled() {
        return properties.gate().enabled();
    }

    private String gateLabel() {
        return gateEnabled() ? "ON" : "OFF";
    }

    // 이미 잡힌 좌석을 게이트가 거르면 SEAT_TAKEN, 게이트 없이 DB가 거르면 SEAT_STATE_CHANGED
    private ErrorCode seatConflictCode() {
        return gateEnabled() ? ErrorCode.SEAT_TAKEN : ErrorCode.SEAT_STATE_CHANGED;
    }

    private double dbTotal() {
        return dbCounter("ok") + dbCounter("state_changed") + dbCounter("user_has_hold");
    }

    private double gateCounter(String result) {
        return meterRegistry.counter("booking.hold.gate", "result", result).count();
    }

    private static boolean isDeadlock(Throwable t) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            if (c instanceof PessimisticLockingFailureException
                    || String.valueOf(c.getMessage()).toLowerCase().contains("deadlock")) {
                return true;
            }
        }
        return false;
    }

    private double dbCounter(String result) {
        return meterRegistry.counter("booking.hold.db", "result", result).count();
    }

    private List<Long> seatIds(long pid) {
        return jdbcTemplate.queryForList("SELECT id FROM seat WHERE performance_id = ? ORDER BY id", Long.class, pid);
    }
}
