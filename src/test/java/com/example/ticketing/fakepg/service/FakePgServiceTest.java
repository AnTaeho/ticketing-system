package com.example.ticketing.fakepg.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.ticketing.common.config.TicketingProperties;
import com.example.ticketing.fakepg.domain.FakePgScenario;
import com.example.ticketing.fakepg.domain.FakePgStatus;
import com.example.ticketing.fakepg.dto.FakePgReply;
import com.example.ticketing.support.FakePgTestConfig;
import com.example.ticketing.support.FakePgTestConfig.GatedSleeper;
import com.example.ticketing.support.IntegrationTest;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

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
    @DisplayName("같은 orderId로 두 번 승인하면 같은 pgTxId를 돌려주고 행은 하나다")
    void approveTwiceIsIdempotent() {
        String orderId = newOrderId();

        FakePgReply first = fakePgService.approve(orderId, 50_000, FakePgScenario.SUCCESS);
        FakePgReply second = fakePgService.approve(orderId, 50_000, FakePgScenario.SUCCESS);

        assertThat(first.status()).isEqualTo("APPROVED");
        assertThat(first.pgTxId()).isNotBlank();
        assertThat(second).isEqualTo(first);
        assertThat(rowCount(orderId)).isEqualTo(1);
        assertThat(row(orderId)).containsExactly("APPROVED", first.pgTxId(), "50000");
        assertThat(sleeper.calls()).isEmpty();
    }

    @Test
    @DisplayName("거절된 주문에 승인이 다시 오면 그대로 DECLINED이고 지연 없이 바로 답한다")
    void declineTwice() {
        String orderId = newOrderId();

        FakePgReply first = fakePgService.approve(orderId, 1000, FakePgScenario.DECLINE);
        FakePgReply second = fakePgService.approve(orderId, 1000, FakePgScenario.SUCCESS);

        assertThat(first).isEqualTo(new FakePgReply(orderId, "DECLINED", null, null));
        assertThat(second).isEqualTo(first);
        assertThat(row(orderId)).containsExactly("DECLINED", null, "1000");
        assertThat(sleeper.calls()).isEmpty();
    }

    @Test
    @DisplayName("취소가 먼저 오면 CANCELLED 묘비를 남기고, 뒤늦은 승인은 DECLINED(reason CANCELLED)로 거절한다")
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
    @DisplayName("PROCESSING 중 취소는 PROCESSING(409)으로 거절, 승인 재요청은 PROCESSING, 끝나면 APPROVED, 그 뒤 취소는 CANCELLED")
    void cancelWhileProcessing() throws Exception {
        String orderId = newOrderId();
        sleeper.close();
        CompletableFuture<FakePgReply> slow = CompletableFuture.supplyAsync(
                () -> fakePgService.approve(orderId, 70_000, FakePgScenario.SLOW_SUCCESS));
        try {
            assertThat(sleeper.awaitEntered(1)).isTrue();

            assertThat(fakePgService.query(orderId)).isEqualTo(new FakePgReply(orderId, "PROCESSING", null, null));
            FakePgReply cancelDuring = fakePgService.cancel(orderId);
            assertThat(cancelDuring.is(FakePgStatus.PROCESSING)).isTrue();
            assertThat(cancelDuring).isEqualTo(new FakePgReply(orderId, "PROCESSING", null, null));
            assertThat(fakePgService.approve(orderId, 70_000, FakePgScenario.SUCCESS))
                    .isEqualTo(new FakePgReply(orderId, "PROCESSING", null, null));
            assertThat(row(orderId).get(0)).isEqualTo("PROCESSING");
        } finally {
            sleeper.open();
        }

        FakePgReply approved = slow.get(10, TimeUnit.SECONDS);
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
    @DisplayName("승인 후 취소하면 CANCELLED, 다시 취소해도 CANCELLED다")
    void cancelApprovedIsIdempotent() {
        String orderId = newOrderId();
        FakePgReply approved = fakePgService.approve(orderId, 1000, FakePgScenario.SUCCESS);

        FakePgReply first = fakePgService.cancel(orderId);
        FakePgReply second = fakePgService.cancel(orderId);

        assertThat(first).isEqualTo(new FakePgReply(orderId, "CANCELLED", approved.pgTxId(), null));
        assertThat(second).isEqualTo(first);
        assertThat(fakePgService.query(orderId)).isEqualTo(first);
        assertThat(rowCount(orderId)).isEqualTo(1);
    }

    @Test
    @DisplayName("거절된 주문을 취소하면 DECLINED 그대로다")
    void cancelDeclinedStaysDeclined() {
        String orderId = newOrderId();
        fakePgService.approve(orderId, 1000, FakePgScenario.DECLINE);

        assertThat(fakePgService.cancel(orderId)).isEqualTo(new FakePgReply(orderId, "DECLINED", null, null));
        assertThat(row(orderId).get(0)).isEqualTo("DECLINED");
    }

    @Test
    @DisplayName("없는 주문을 조회하면 NOT_FOUND이고 행을 만들지 않는다")
    void queryMissing() {
        String orderId = newOrderId();

        FakePgReply reply = fakePgService.query(orderId);

        assertThat(reply).isEqualTo(new FakePgReply(orderId, "NOT_FOUND", null, null));
        assertThat(reply.isNotFound()).isTrue();
        assertThat(rowCount(orderId)).isZero();
    }

    @Test
    @DisplayName("SLOW_DECLINE은 slow-delay-ms만큼 지연한 뒤 DECLINED를 저장한다")
    void slowDecline() {
        String orderId = newOrderId();

        FakePgReply reply = fakePgService.approve(orderId, 1000, FakePgScenario.SLOW_DECLINE);

        assertThat(reply).isEqualTo(new FakePgReply(orderId, "DECLINED", null, null));
        assertThat(sleeper.calls()).containsExactly(properties.fakePg().slowDelay().toMillis());
        assertThat(row(orderId).get(0)).isEqualTo("DECLINED");
    }

    @Test
    @DisplayName("시나리오가 없으면 설정 범위의 지연 뒤 APPROVED 또는 DECLINED로 끝난다")
    void randomScenario() {
        TicketingProperties.FakePg config = properties.fakePg();
        for (int i = 0; i < 20; i++) {
            String orderId = newOrderId();

            FakePgReply reply = fakePgService.approve(orderId, 1000, null);

            assertThat(reply.status()).isIn("APPROVED", "DECLINED");
            assertThat(row(orderId).get(0)).isEqualTo(reply.status());
        }
        assertThat(sleeper.calls()).hasSize(20).allSatisfy(ms -> assertThat(ms == config.slowDelay().toMillis()
                || (ms >= config.delayMin().toMillis() && ms <= config.delayMax().toMillis())).isTrue());
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
