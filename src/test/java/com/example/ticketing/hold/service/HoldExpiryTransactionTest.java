package com.example.ticketing.hold.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.ticketing.hold.dto.CreateHoldCommand;
import com.example.ticketing.hold.dto.CreateHoldResult;
import com.example.ticketing.hold.dto.HoldExpiry;
import com.example.ticketing.support.Fixtures;
import com.example.ticketing.support.IntegrationTest;
import com.example.ticketing.support.RedisKeyCleaner;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

@IntegrationTest
class HoldExpiryTransactionTest {

    private static final int BATCH = 50;

    @Autowired
    private HoldExpiryTransaction expiryTransaction;
    @Autowired
    private SeatHoldService seatHoldService;
    @Autowired
    private Fixtures fixtures;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private ApplicationContext context;
    @Autowired
    private RedisKeyCleaner redisKeyCleaner;

    @Test
    @DisplayName("만료 hold 120개를 4스레드가 동시에 치우면 각 hold는 정확히 한 번 EXPIRED, 좌석은 모두 AVAILABLE")
    void concurrentClaimExpiresEachHoldOnce() throws InterruptedException {
        int holdCount = 120;
        int threadCount = 4;
        long pid = fixtures.newPerformance(1, 6, holdCount * 2 / 6);
        List<Long> ids = seatIds(pid);
        Map<Long, List<Long>> seatsByHold = new HashMap<>();
        for (int i = 0; i < holdCount; i++) {
            List<Long> seats = List.of(ids.get(i * 2), ids.get(i * 2 + 1));
            CreateHoldResult created = holdAsNewUser(pid, seats);
            seatsByHold.put(created.holdId(), seats);
        }
        expirePerformance(pid);

        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch ready = new CountDownLatch(threadCount);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threadCount);
        List<HoldExpiry> returned = new CopyOnWriteArrayList<>();
        List<Throwable> unexpected = new CopyOnWriteArrayList<>();
        AtomicInteger calls = new AtomicInteger();
        for (int t = 0; t < threadCount; t++) {
            executor.submit(() -> {
                try {
                    ready.countDown();
                    start.await();
                    List<HoldExpiry> batch;
                    do {
                        batch = expiryTransaction.claimAndExpire(BATCH);
                        calls.incrementAndGet();
                        returned.addAll(batch);
                    } while (!batch.isEmpty());
                } catch (Throwable e) {
                    unexpected.add(e);
                } finally {
                    done.countDown();
                }
            });
        }
        assertThat(ready.await(30, TimeUnit.SECONDS)).isTrue();
        start.countDown();
        boolean finished = done.await(60, TimeUnit.SECONDS);
        executor.shutdownNow();
        System.out.printf("[S2.2] 만료 4스레드: 호출 %d, 반환 %d건(이 회차 외 잔여 포함), 예기치 못한 예외 %d%n",
                calls.get(), returned.size(), unexpected.size());

        assertThat(finished).isTrue();
        unexpected.forEach(Throwable::printStackTrace);
        assertThat(unexpected).isEmpty();
        List<Long> allReturnedIds = returned.stream().map(HoldExpiry::holdId).toList();
        assertThat(new HashSet<>(allReturnedIds)).hasSameSizeAs(allReturnedIds);

        List<HoldExpiry> mine = returned.stream().filter(h -> h.gate().performanceId() == pid).toList();
        assertThat(mine).extracting(HoldExpiry::holdId).containsExactlyInAnyOrderElementsOf(seatsByHold.keySet());
        assertThat(mine).allSatisfy(h ->
                assertThat(h.gate().seatIds()).containsExactlyInAnyOrderElementsOf(seatsByHold.get(h.holdId())));
        assertThat(jdbcTemplate.queryForList(
                "SELECT DISTINCT status FROM seat_hold WHERE performance_id = ?", String.class, pid))
                .containsExactly("EXPIRED");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM seat WHERE performance_id = ? "
                        + "AND status = 'AVAILABLE' AND hold_id IS NULL AND held_until IS NULL",
                Long.class, pid)).isEqualTo(holdCount * 2L);
    }

    @Test
    @DisplayName("PAYING은 expires_at이 지나도 만료되지 않고 좌석도 HELD로 남는다")
    void payingIsNotExpired() {
        long pid = fixtures.newPerformance(1, 1, 4);
        List<Long> target = seatIds(pid).subList(0, 2);
        CreateHoldResult hold = holdAsNewUser(pid, target);
        jdbcTemplate.update("UPDATE seat_hold SET status = 'PAYING', expires_at = NOW(6) - INTERVAL 1 MINUTE "
                + "WHERE id = ?", hold.holdId());

        List<HoldExpiry> returned = drain();

        assertThat(returned).extracting(HoldExpiry::holdId).doesNotContain(hold.holdId());
        assertThat(holdStatus(hold.holdId())).isEqualTo("PAYING");
        assertThat(heldSeats(pid, hold.holdId())).containsExactlyElementsOf(target);
    }

    @Test
    @DisplayName("늦게 치운 A의 hold는 B가 가져간 좌석을 건드리지 않는다")
    void lateExpiryKeepsOthersSeats() {
        long pid = fixtures.newPerformance(1, 1, 4);
        List<Long> target = seatIds(pid).subList(0, 2);
        CreateHoldResult holdA = holdAsNewUser(pid, target);
        expireHold(holdA.holdId());
        // 게이트 키는 선점과 같은 TTL이라 만료 시각이 지나면 함께 사라진다. DB 시각만 당겼으므로 키도 지운다
        redisKeyCleaner.clean(pid);
        CreateHoldResult holdB = holdAsNewUser(pid, target);

        List<HoldExpiry> returned = drain();

        assertThat(holdStatus(holdA.holdId())).isEqualTo("EXPIRED");
        assertThat(holdStatus(holdB.holdId())).isEqualTo("HELD");
        assertThat(heldSeats(pid, holdB.holdId())).containsExactlyElementsOf(target);
        assertThat(returned).filteredOn(h -> h.holdId() == holdA.holdId())
                .singleElement()
                .satisfies(h -> assertThat(h.gate().seatIds()).isEmpty());
        assertThat(returned).extracting(HoldExpiry::holdId).doesNotContain(holdB.holdId());
        redisKeyCleaner.clean(pid);
    }

    @Test
    @DisplayName("expires_at이 아직 남은 hold는 건드리지 않는다")
    void notYetExpiredIsUntouched() {
        long pid = fixtures.newPerformance(1, 1, 4);
        List<Long> target = seatIds(pid).subList(0, 2);
        CreateHoldResult hold = holdAsNewUser(pid, target);

        List<HoldExpiry> returned = drain();

        assertThat(returned).extracting(HoldExpiry::holdId).doesNotContain(hold.holdId());
        assertThat(holdStatus(hold.holdId())).isEqualTo("HELD");
        assertThat(heldSeats(pid, hold.holdId())).containsExactlyElementsOf(target);
    }

    @Test
    @DisplayName("테스트 컨텍스트에는 만료 스케줄러 빈이 없다(expiry-enabled=false)")
    void schedulerBeanDisabledInTests() {
        assertThat(context.getBeanProvider(HoldExpiryScheduler.class).getIfAvailable()).isNull();
        assertThat(context.getBeanProvider(HoldExpiryTransaction.class).getIfAvailable()).isNotNull();
    }

    // 다른 테스트가 남긴 만료 hold가 있을 수 있어 빌 때까지 치운다
    private List<HoldExpiry> drain() {
        List<HoldExpiry> all = new ArrayList<>();
        List<HoldExpiry> batch;
        do {
            batch = expiryTransaction.claimAndExpire(BATCH);
            all.addAll(batch);
        } while (batch.size() == BATCH);
        return all;
    }

    private void expirePerformance(long pid) {
        jdbcTemplate.update("UPDATE seat_hold SET expires_at = NOW(6) - INTERVAL 1 MINUTE WHERE performance_id = ?", pid);
        jdbcTemplate.update("UPDATE seat SET held_until = NOW(6) - INTERVAL 1 MINUTE "
                + "WHERE performance_id = ? AND status = 'HELD'", pid);
    }

    private void expireHold(long holdId) {
        jdbcTemplate.update("UPDATE seat_hold SET expires_at = NOW(6) - INTERVAL 1 MINUTE WHERE id = ?", holdId);
        jdbcTemplate.update("UPDATE seat SET held_until = NOW(6) - INTERVAL 1 MINUTE WHERE hold_id = ?", holdId);
    }

    private String holdStatus(long holdId) {
        return jdbcTemplate.queryForObject("SELECT status FROM seat_hold WHERE id = ?", String.class, holdId);
    }

    private List<Long> heldSeats(long pid, long holdId) {
        return jdbcTemplate.queryForList("SELECT id FROM seat WHERE performance_id = ? AND hold_id = ? "
                + "AND status = 'HELD' ORDER BY id", Long.class, pid, holdId);
    }

    private List<Long> seatIds(long pid) {
        return jdbcTemplate.queryForList("SELECT id FROM seat WHERE performance_id = ? ORDER BY id", Long.class, pid);
    }

    private CreateHoldResult holdAsNewUser(long pid, List<Long> seats) {
        long userId = fixtures.newUser();
        return seatHoldService.hold(new CreateHoldCommand(userId, pid, seats, fixtures.admit(pid, userId)));
    }
}
