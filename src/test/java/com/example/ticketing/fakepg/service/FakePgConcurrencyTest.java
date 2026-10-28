package com.example.ticketing.fakepg.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.ticketing.common.config.TicketingProperties;
import com.example.ticketing.fakepg.domain.FakePgScenario;
import com.example.ticketing.fakepg.domain.FakePgStatus;
import com.example.ticketing.fakepg.dto.FakePgReply;
import com.example.ticketing.support.FakePgTestConfig;
import com.example.ticketing.support.FakePgTestConfig.GatedSleeper;
import com.example.ticketing.support.Await;
import com.example.ticketing.support.Concurrently;
import com.example.ticketing.support.Concurrently.Outcome;
import com.example.ticketing.support.Concurrently.Results;
import com.example.ticketing.support.Concurrently.Running;
import com.example.ticketing.support.IntegrationTest;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

@IntegrationTest
@Import(FakePgTestConfig.class)
class FakePgConcurrencyTest {

    private static final int THREAD_COUNT = 10;
    private static final int RACE_ROUNDS = 30;
    private static final Duration TEN_SECONDS = Duration.ofSeconds(10);

    @Autowired
    private FakePgService fakePgService;
    @Autowired
    private GatedSleeper sleeper;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private TicketingProperties properties;

    @BeforeEach
    void setUp() {
        sleeper.reset();
    }

    @AfterEach
    void tearDown() {
        sleeper.reset();
    }

    @Test
    @DisplayName("같은 주문에 10건이 동시에 승인을 요청해도 승인은 한 번만 처리한다")
    void sameOrderTenThreads() throws Exception {
        String orderId = "c-" + UUID.randomUUID();
        sleeper.close();

        Results<FakePgReply> results;
        try (Running<FakePgReply> running = Concurrently.of(THREAD_COUNT).readyWithin(TEN_SECONDS).within(TEN_SECONDS)
                .start(i -> fakePgService.approve(orderId, 30_000, FakePgScenario.SLOW_SUCCESS))) {
            try {
                // 이긴 1건은 지연에 묶여 있고 나머지 9건은 PROCESSING을 받고 끝나야 한다
                assertThat(sleeper.awaitEntered(1)).isTrue();
                List<Outcome<FakePgReply>> early = Await.until(TEN_SECONDS, Duration.ofMillis(10),
                        running::finishedSoFar, finished -> finished.size() >= THREAD_COUNT - 1);
                assertThat(early).hasSize(THREAD_COUNT - 1).allSatisfy(o -> {
                    assertThat(o.error()).isNull();
                    assertThat(o.value()).isEqualTo(new FakePgReply(orderId, "PROCESSING", null, null));
                });
            } finally {
                sleeper.open();
            }
            results = running.await();
        }

        assertThat(results.errors()).isEmpty();
        List<FakePgReply> approved = results.values().stream().filter(r -> r.is(FakePgStatus.APPROVED)).toList();
        assertThat(approved).hasSize(1);
        assertThat(sleeper.calls()).containsExactly(properties.fakePg().slowDelay().toMillis());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM fake_pg_transaction WHERE order_id = ?", Long.class, orderId)).isEqualTo(1);
        assertThat(fakePgService.query(orderId))
                .isEqualTo(new FakePgReply(orderId, "APPROVED", approved.get(0).pgTxId(), null));
        assertThat(fakePgService.approve(orderId, 30_000, FakePgScenario.SUCCESS)).isEqualTo(approved.get(0));
    }

    @Test
    @DisplayName("없는 주문에 10건이 동시에 취소하면 묘비 한 행만 남고 모두 취소로 답한다")
    void concurrentTombstones() {
        String orderId = "c-" + UUID.randomUUID();

        List<Outcome<FakePgReply>> outcomes = race(THREAD_COUNT).run(i -> fakePgService.cancel(orderId)).outcomes();

        assertThat(outcomes).allSatisfy(o -> {
            assertThat(o.error()).isNull();
            assertThat(o.value()).isEqualTo(new FakePgReply(orderId, "CANCELLED", null, null));
        });
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM fake_pg_transaction WHERE order_id = ?", Long.class, orderId)).isEqualTo(1);
    }

    @Test
    @DisplayName("승인과 취소가 겹쳐도 취소를 받아 준 주문은 승인으로 남지 않는다")
    void approveCancelRace() {
        for (int round = 0; round < RACE_ROUNDS; round++) {
            String orderId = "r-" + UUID.randomUUID();

            List<Outcome<FakePgReply>> outcomes = race(2).run(i -> i == 0
                    ? fakePgService.approve(orderId, 1000, FakePgScenario.SUCCESS)
                    : fakePgService.cancel(orderId)).outcomes();

            Outcome<FakePgReply> approve = outcomes.get(0);
            Outcome<FakePgReply> cancel = outcomes.get(1);
            assertThat(approve.error()).isNull();
            assertThat(cancel.error()).isNull();
            String finalStatus = jdbcTemplate.queryForObject(
                    "SELECT status FROM fake_pg_transaction WHERE order_id = ?", String.class, orderId);
            if (cancel.value().is(FakePgStatus.PROCESSING)) {
                // 승인이 먼저 PROCESSING을 넣었고 취소는 409로 거절됐다
                assertThat(approve.value().status()).isEqualTo("APPROVED");
                assertThat(finalStatus).isEqualTo("APPROVED");
            } else {
                assertThat(cancel.value().status()).isEqualTo("CANCELLED");
                assertThat(finalStatus).isEqualTo("CANCELLED");
                assertThat(approve.value().status()).isIn("APPROVED", "DECLINED");
                if (approve.value().is(FakePgStatus.DECLINED)) {
                    assertThat(approve.value().reason()).isEqualTo("CANCELLED");
                }
            }
        }
    }

    private static Concurrently race(int tasks) {
        return Concurrently.of(tasks).readyWithin(TEN_SECONDS).within(TEN_SECONDS);
    }
}
