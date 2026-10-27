package com.example.ticketing.booking.hold;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.ticketing.booking.common.BookingErrorCode;
import com.example.ticketing.booking.common.BookingException;
import com.example.ticketing.booking.hold.dto.HoldCreated;
import com.example.ticketing.booking.support.BookingFixtures;
import com.example.ticketing.booking.support.BookingIntegrationTest;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.ArrayList;
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

@BookingIntegrationTest
class SeatHoldConcurrencyTest {

    private static final int THREAD_COUNT = 500;
    private static final int CROSS_ROUNDS = 200;

    @Autowired
    private SeatHoldService seatHoldService;
    @Autowired
    private BookingFixtures fixtures;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private MeterRegistry meterRegistry;

    @Test
    @DisplayName("같은 2석에 500명이 동시에 선점하면 성공 1, 나머지는 409 SEAT_STATE_CHANGED")
    void sameSeatsFiveHundredUsers() throws InterruptedException {
        long pid = fixtures.newPerformance(1, 1, 4);
        List<Long> target = seatIds(pid).subList(0, 2);
        List<Long> users = new ArrayList<>(THREAD_COUNT);
        for (int i = 0; i < THREAD_COUNT; i++) {
            users.add(fixtures.newUser());
        }
        double okBefore = dbCounter("ok");
        double changedBefore = dbCounter("state_changed");

        ExecutorService executor = Executors.newFixedThreadPool(THREAD_COUNT);
        CountDownLatch ready = new CountDownLatch(THREAD_COUNT);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(THREAD_COUNT);
        AtomicInteger success = new AtomicInteger();
        Map<BookingErrorCode, AtomicInteger> rejected = new ConcurrentHashMap<>();
        List<Throwable> unexpected = new CopyOnWriteArrayList<>();

        for (long userId : users) {
            executor.submit(() -> {
                try {
                    ready.countDown();
                    start.await();
                    seatHoldService.hold(userId, pid, target, null);
                    success.incrementAndGet();
                } catch (BookingException e) {
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
        System.out.printf("[S2.1] 500명 동시 선점: %dms, 성공 %d, 거절 %s, 예기치 못한 예외 %d%n",
                elapsedMs, success.get(), rejected, unexpected.size());

        assertThat(finished).isTrue();
        unexpected.forEach(Throwable::printStackTrace);
        assertThat(unexpected).isEmpty();
        assertThat(success.get()).isEqualTo(1);
        assertThat(rejected).containsOnlyKeys(BookingErrorCode.SEAT_STATE_CHANGED);
        assertThat(rejected.get(BookingErrorCode.SEAT_STATE_CHANGED).get()).isEqualTo(THREAD_COUNT - 1);

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
        assertThat(dbCounter("state_changed") - changedBefore).isEqualTo(THREAD_COUNT - 1);
    }

    @Test
    @DisplayName("교차 선점 A[1,2] / B[2,1]를 200회 반복해도 데드락 0, 매 회 성공 1")
    void crossOrderNoDeadlock() throws InterruptedException {
        long pid = fixtures.newPerformance(1, 1, 2);
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
                AtomicInteger stateChanged = new AtomicInteger();
                for (Object[] attempt : new Object[][]{{userA, forward}, {userB, reverse}}) {
                    long userId = (long) attempt[0];
                    @SuppressWarnings("unchecked")
                    List<Long> seats = (List<Long>) attempt[1];
                    executor.submit(() -> {
                        try {
                            start.await();
                            created.add(seatHoldService.hold(userId, pid, seats, null));
                        } catch (BookingException e) {
                            if (e.getCode() == BookingErrorCode.SEAT_STATE_CHANGED) {
                                stateChanged.incrementAndGet();
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
                if (created.size() != 1 || stateChanged.get() != 1) {
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
        System.out.printf("[S2.1] 교차 선점 %d회: %dms, 데드락 %d, 성공 1이 아닌 회차 %s, 예기치 못한 예외 %d%n",
                CROSS_ROUNDS, elapsedMs, deadlocks.get(), badRounds, unexpected.size());

        unexpected.forEach(Throwable::printStackTrace);
        assertThat(deadlocks.get()).isZero();
        assertThat(unexpected).isEmpty();
        assertThat(badRounds).isEmpty();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM seat_hold WHERE performance_id = ? AND status = 'RELEASED'",
                Long.class, pid)).isEqualTo(CROSS_ROUNDS);
    }

    @Test
    @DisplayName("같은 사용자가 서로 다른 좌석으로 동시에 10번 선점해도 성공 1, 나머지는 USER_HAS_HOLD")
    void sameUserConcurrent() throws InterruptedException {
        int attempts = 10;
        long pid = fixtures.newPerformance(1, 1, attempts);
        List<Long> ids = seatIds(pid);
        long userId = fixtures.newUser();

        ExecutorService executor = Executors.newFixedThreadPool(attempts);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(attempts);
        AtomicInteger success = new AtomicInteger();
        Map<BookingErrorCode, AtomicInteger> rejected = new ConcurrentHashMap<>();
        List<Throwable> unexpected = new CopyOnWriteArrayList<>();
        for (int i = 0; i < attempts; i++) {
            long seatId = ids.get(i);
            executor.submit(() -> {
                try {
                    start.await();
                    seatHoldService.hold(userId, pid, List.of(seatId), null);
                    success.incrementAndGet();
                } catch (BookingException e) {
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
        assertThat(rejected).containsOnlyKeys(BookingErrorCode.USER_HAS_HOLD);
        assertThat(rejected.get(BookingErrorCode.USER_HAS_HOLD).get()).isEqualTo(attempts - 1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM seat_hold WHERE performance_id = ? AND user_id = ?",
                Long.class, pid, userId)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM seat WHERE performance_id = ? AND status = 'HELD'",
                Long.class, pid)).isEqualTo(1);
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
