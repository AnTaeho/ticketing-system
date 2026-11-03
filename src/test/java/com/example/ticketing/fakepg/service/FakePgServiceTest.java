package com.example.ticketing.fakepg.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.ticketing.common.config.TicketingProperties;
import com.example.ticketing.fakepg.domain.FakePgScenario;
import com.example.ticketing.fakepg.domain.FakePgStatus;
import com.example.ticketing.fakepg.dto.FakePgReply;
import com.example.ticketing.support.FakePgTestConfig;
import com.example.ticketing.support.FakePgTestConfig.GatedSleeper;
import com.example.ticketing.support.Concurrently;
import com.example.ticketing.support.Concurrently.Results;
import com.example.ticketing.support.Concurrently.Running;
import com.example.ticketing.support.IntegrationTest;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

// 부하 테스트와 결제 대사가 기대는 규칙(묘비, 처리 중 취소, 취소 멱등)만 본다. 결론 비율은 FakePgDeciderTest가 본다
@IntegrationTest
@Import(FakePgTestConfig.class)
class FakePgServiceTest {

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
    @DisplayName("취소가 먼저 오면 묘비를 남겨 뒤늦은 승인을 거절한다")
    void tombstoneRejectsLateApprove() {
        String orderId = newOrderId();

        FakePgReply cancel = fakePgService.cancel(orderId);
        FakePgReply lateApprove = fakePgService.approve(orderId, 1000, FakePgScenario.SUCCESS);

        assertThat(cancel).isEqualTo(new FakePgReply(orderId, "CANCELLED", null, null));
        assertThat(lateApprove).isEqualTo(new FakePgReply(orderId, "DECLINED", null, "CANCELLED"));
        assertThat(rowCount(orderId)).isEqualTo(1);
        assertThat(row(orderId)).containsExactly("CANCELLED", null, "0");
        assertThat(fakePgService.query(orderId).status()).isEqualTo("CANCELLED");
        assertThat(sleeper.calls()).isEmpty();
    }

    @Test
    @DisplayName("승인이 지연되는 동안 온 취소는 거절하고 승인이 끝난 뒤의 취소는 받는다")
    void cancelWhileProcessing() throws Exception {
        String orderId = newOrderId();
        sleeper.close();

        Results<FakePgReply> results;
        try (Running<FakePgReply> slow = Concurrently.of(1).within(Duration.ofSeconds(10))
                .start(i -> fakePgService.approve(orderId, 70_000, FakePgScenario.SLOW_SUCCESS))) {
            try {
                assertThat(sleeper.awaitEntered(1)).isTrue();

                assertThat(fakePgService.query(orderId))
                        .isEqualTo(new FakePgReply(orderId, "PROCESSING", null, null));
                FakePgReply cancelDuring = fakePgService.cancel(orderId);
                assertThat(cancelDuring.is(FakePgStatus.PROCESSING)).isTrue();
                assertThat(cancelDuring).isEqualTo(new FakePgReply(orderId, "PROCESSING", null, null));
                assertThat(fakePgService.approve(orderId, 70_000, FakePgScenario.SUCCESS))
                        .isEqualTo(new FakePgReply(orderId, "PROCESSING", null, null));
                assertThat(row(orderId).get(0)).isEqualTo("PROCESSING");
            } finally {
                sleeper.open();
            }
            results = slow.await();
        }

        assertThat(results.errors()).isEmpty();
        FakePgReply approved = results.values().get(0);
        assertThat(approved.status()).isEqualTo("APPROVED");
        assertThat(approved.pgTxId()).isNotBlank();
        assertThat(sleeper.calls()).containsExactly(properties.fakePg().slowDelay().toMillis());

        FakePgReply cancelled = fakePgService.cancel(orderId);
        assertThat(cancelled).isEqualTo(new FakePgReply(orderId, "CANCELLED", approved.pgTxId(), null));
        assertThat(fakePgService.cancel(orderId)).isEqualTo(cancelled);
        assertThat(row(orderId)).containsExactly("CANCELLED", approved.pgTxId(), "70000");
        assertThat(fakePgService.approve(orderId, 70_000, FakePgScenario.SUCCESS))
                .isEqualTo(new FakePgReply(orderId, "DECLINED", null, "CANCELLED"));
    }

    @Test
    @DisplayName("승인한 주문은 몇 번 취소해도 같은 취소 결과를, 거절된 주문은 DECLINED 그대로를 돌려준다")
    void cancelIsIdempotent() {
        String orderId = newOrderId();
        String declinedOrder = newOrderId();
        FakePgReply approved = fakePgService.approve(orderId, 1000, FakePgScenario.SUCCESS);
        fakePgService.approve(declinedOrder, 1000, FakePgScenario.DECLINE);

        FakePgReply first = fakePgService.cancel(orderId);
        FakePgReply second = fakePgService.cancel(orderId);

        assertThat(first).isEqualTo(new FakePgReply(orderId, "CANCELLED", approved.pgTxId(), null));
        assertThat(second).isEqualTo(first);
        assertThat(fakePgService.query(orderId)).isEqualTo(first);
        assertThat(rowCount(orderId)).isEqualTo(1);
        assertThat(fakePgService.cancel(declinedOrder))
                .isEqualTo(new FakePgReply(declinedOrder, "DECLINED", null, null));
        assertThat(row(declinedOrder).get(0)).isEqualTo("DECLINED");
    }


    private static String newOrderId() {
        return "t-" + UUID.randomUUID();
    }

    private long rowCount(String orderId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM fake_pg_transaction WHERE order_id = ?", Long.class, orderId);
    }

    private List<String> row(String orderId) {
        return jdbcTemplate.queryForObject(
                "SELECT status, pg_tx_id, amount FROM fake_pg_transaction WHERE order_id = ?",
                (rs, i) -> Arrays.asList(rs.getString(1), rs.getString(2), rs.getString(3)), orderId);
    }
}
