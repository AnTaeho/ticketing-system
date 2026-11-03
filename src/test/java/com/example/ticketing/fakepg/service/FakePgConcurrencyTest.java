package com.example.ticketing.fakepg.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.ticketing.fakepg.domain.FakePgScenario;
import com.example.ticketing.fakepg.domain.FakePgStatus;
import com.example.ticketing.fakepg.dto.FakePgReply;
import com.example.ticketing.support.Concurrently;
import com.example.ticketing.support.Concurrently.Outcome;
import com.example.ticketing.support.IntegrationTest;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

@IntegrationTest
class FakePgConcurrencyTest {

    private static final int THREAD_COUNT = 10;
    private static final int RACE_ROUNDS = 30;
    private static final Duration TEN_SECONDS = Duration.ofSeconds(10);

    @Autowired
    private FakePgService fakePgService;
    @Autowired
    private JdbcTemplate jdbcTemplate;

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
