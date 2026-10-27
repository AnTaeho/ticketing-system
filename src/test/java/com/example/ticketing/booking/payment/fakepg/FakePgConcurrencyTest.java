package com.example.ticketing.booking.payment.fakepg;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.ticketing.booking.BookingProperties;
import com.example.ticketing.booking.payment.fakepg.FakePgTestConfig.GatedSleeper;
import com.example.ticketing.booking.support.BookingIntegrationTest;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

@BookingIntegrationTest
@Import(FakePgTestConfig.class)
class FakePgConcurrencyTest {

    private static final int THREAD_COUNT = 10;
    private static final int RACE_ROUNDS = 30;

    @Autowired
    private FakePgService fakePgService;
    @Autowired
    private GatedSleeper sleeper;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private BookingProperties properties;

    @BeforeEach
    void setUp() {
        sleeper.reset();
    }

    @AfterEach
    void tearDown() {
        sleeper.reset();
    }

    @Test
    @DisplayName("같은 orderId로 10스레드가 동시에 승인하면 행 1개, 지연 후 승인 처리는 1번, pgTxId는 하나다")
    void sameOrderTenThreads() throws Exception {
        String orderId = "c-" + UUID.randomUUID();
        sleeper.close();
        ExecutorService executor = Executors.newFixedThreadPool(THREAD_COUNT);
        CountDownLatch ready = new CountDownLatch(THREAD_COUNT);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(THREAD_COUNT);
        List<FakePgReply> replies = new CopyOnWriteArrayList<>();
        List<Throwable> unexpected = new CopyOnWriteArrayList<>();
        try {
            for (int i = 0; i < THREAD_COUNT; i++) {
                executor.submit(() -> {
                    try {
                        ready.countDown();
                        start.await();
                        replies.add(fakePgService.approve(orderId, 30_000, FakePgScenario.SLOW_SUCCESS));
                    } catch (Throwable t) {
                        unexpected.add(t);
                    } finally {
                        done.countDown();
                    }
                });
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            // 이긴 1건은 지연에 묶여 있고 나머지 9건은 PROCESSING을 받고 끝나야 한다
            assertThat(sleeper.awaitEntered(1)).isTrue();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (replies.size() + unexpected.size() < THREAD_COUNT - 1 && System.nanoTime() < deadline) {
                Thread.sleep(10);
            }
            assertThat(unexpected).isEmpty();
            assertThat(replies).hasSize(THREAD_COUNT - 1)
                    .allSatisfy(r -> assertThat(r).isEqualTo(new FakePgReply(orderId, "PROCESSING", null, null)));
        } finally {
            sleeper.open();
        }
        assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();
        executor.shutdownNow();

        unexpected.forEach(Throwable::printStackTrace);
        assertThat(unexpected).isEmpty();
        List<FakePgReply> approved = replies.stream().filter(r -> r.is(FakePgStatus.APPROVED)).toList();
        System.out.printf("[S4.1] 같은 주문 10스레드 승인: 응답 %s, 지연 호출 %s%n",
                replies.stream().map(FakePgReply::status).toList(), sleeper.calls());

        assertThat(approved).hasSize(1);
        assertThat(sleeper.calls()).containsExactly(properties.fakePg().slowDelayMs());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM fake_pg_transaction WHERE order_id = ?", Long.class, orderId)).isEqualTo(1);
        assertThat(fakePgService.query(orderId))
                .isEqualTo(new FakePgReply(orderId, "APPROVED", approved.get(0).pgTxId(), null));
        assertThat(fakePgService.approve(orderId, 30_000, FakePgScenario.SUCCESS)).isEqualTo(approved.get(0));
    }

    @Test
    @DisplayName("없는 주문에 10스레드가 동시에 취소하면 묘비 1행, 모두 CANCELLED")
    void concurrentTombstones() throws Exception {
        String orderId = "c-" + UUID.randomUUID();

        List<Outcome> outcomes = race(List.of(), THREAD_COUNT, () -> fakePgService.cancel(orderId));

        assertThat(outcomes).allSatisfy(o -> {
            assertThat(o.error()).isNull();
            assertThat(o.reply()).isEqualTo(new FakePgReply(orderId, "CANCELLED", null, null));
        });
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM fake_pg_transaction WHERE order_id = ?", Long.class, orderId)).isEqualTo(1);
    }

    @Test
    @DisplayName("승인과 취소가 동시에 와도 취소가 CANCELLED로 답한 주문은 끝내 APPROVED로 남지 않는다(30회)")
    void approveCancelRace() throws Exception {
        List<String> shapes = new ArrayList<>();
        for (int round = 0; round < RACE_ROUNDS; round++) {
            String orderId = "r-" + UUID.randomUUID();
            List<Outcome> outcomes = race(
                    List.of(() -> fakePgService.approve(orderId, 1000, FakePgScenario.SUCCESS)),
                    1, () -> fakePgService.cancel(orderId));
            Outcome approve = outcomes.get(0);
            Outcome cancel = outcomes.get(1);
            assertThat(approve.error()).isNull();
            assertThat(cancel.error()).isNull();

            String finalStatus = jdbcTemplate.queryForObject(
                    "SELECT status FROM fake_pg_transaction WHERE order_id = ?", String.class, orderId);
            shapes.add(approve.reply().status() + "/" + cancel.reply().status() + "→" + finalStatus);

            if (cancel.reply().is(FakePgStatus.PROCESSING)) {
                // 승인이 먼저 PROCESSING을 넣었고 취소는 409로 거절됐다
                assertThat(approve.reply().status()).isEqualTo("APPROVED");
                assertThat(finalStatus).isEqualTo("APPROVED");
            } else {
                assertThat(cancel.reply().status()).isEqualTo("CANCELLED");
                assertThat(finalStatus).isEqualTo("CANCELLED");
                assertThat(approve.reply().status()).isIn("APPROVED", "DECLINED");
                if (approve.reply().is(FakePgStatus.DECLINED)) {
                    assertThat(approve.reply().reason()).isEqualTo("CANCELLED");
                }
            }
        }
        System.out.printf("[S4.1] 승인/취소 경합 %d회 결과(승인/취소→최종): %s%n", RACE_ROUNDS,
                shapes.stream().collect(Collectors.groupingBy(s -> s,
                        TreeMap::new, Collectors.counting())));
    }

    // first의 작업들을 먼저, 그다음 rest 작업을 restCount번 동시에 시작한다. 결과는 제출 순서대로다
    private List<Outcome> race(List<Supplier<FakePgReply>> first, int restCount, Supplier<FakePgReply> rest)
            throws InterruptedException {
        List<Supplier<FakePgReply>> tasks = new ArrayList<>(first);
        for (int i = 0; i < restCount; i++) {
            tasks.add(rest);
        }
        ExecutorService executor = Executors.newFixedThreadPool(tasks.size());
        CountDownLatch ready = new CountDownLatch(tasks.size());
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Outcome>> futures = new ArrayList<>();
        for (Supplier<FakePgReply> task : tasks) {
            futures.add(executor.submit(() -> {
                ready.countDown();
                start.await();
                try {
                    return new Outcome(task.get(), null);
                } catch (Throwable t) {
                    t.printStackTrace();
                    return new Outcome(null, t);
                }
            }));
        }
        assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
        start.countDown();
        List<Outcome> outcomes = new ArrayList<>();
        try {
            for (Future<Outcome> future : futures) {
                outcomes.add(future.get(10, TimeUnit.SECONDS));
            }
        } catch (Exception e) {
            throw new AssertionError(e);
        } finally {
            executor.shutdownNow();
        }
        return outcomes;
    }

    private record Outcome(FakePgReply reply, Throwable error) {
    }
}
