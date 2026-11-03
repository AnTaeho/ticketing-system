package com.example.ticketing.payment.client;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.ticketing.common.config.TicketingProperties;
import com.example.ticketing.payment.dto.PgScenario;
import com.example.ticketing.support.Await;
import com.example.ticketing.support.HttpIntegrationTest;
import com.example.ticketing.support.TestProperties;
import com.example.ticketing.support.TestServices;
import java.io.IOException;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;

// 묘비·취소 규칙은 FakePg 테스트가 본다. 여기서는 승인·거절·시간 초과·연결 실패·취소 409가 PG 상태로 바뀌는지만 본다
@HttpIntegrationTest
class HttpPgClientTest {

    private static final int AMOUNT = 77_000;

    @Autowired
    private PgClient pgClient;
    @Autowired
    private TicketingProperties properties;
    @Autowired
    private Environment environment;
    @Autowired
    private TestServices services;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("설정에 PG 주소가 없어도 실행 중 포트의 Fake PG로 승인하고 조회한다")
    void approveSuccess() {
        assertThat(properties.pg().baseUrl()).isEmpty();
        String orderId = newOrderId();

        PgResult approved = pgClient.approve(orderId, AMOUNT, Optional.of(PgScenario.SUCCESS.name()));

        assertThat(approved.status()).isEqualTo(PgStatus.APPROVED);
        assertThat(approved.pgTxId()).isNotBlank();
        assertThat(approved.detail()).isNull();
        assertThat(pgClient.query(orderId)).isEqualTo(PgResult.of(PgStatus.APPROVED, approved.pgTxId()));
    }

    @Test
    @DisplayName("거절 시나리오로 승인하면 PG 거래 번호 없이 DECLINED다")
    void approveDecline() {
        PgResult declined = pgClient.approve(newOrderId(), AMOUNT, Optional.of(PgScenario.DECLINE.name()));

        assertThat(declined).isEqualTo(PgResult.of(PgStatus.DECLINED, null));
    }

    @Test
    @DisplayName("느린 승인은 읽기 제한 시간(3초)에 걸려 UNKNOWN이고 끝나면 조회는 APPROVED다")
    void slowSuccessTimesOutAsUnknownThenQueryApproved() {
        String orderId = newOrderId();

        long started = System.nanoTime();
        PgResult result = pgClient.approve(orderId, AMOUNT, Optional.of(PgScenario.SLOW_SUCCESS.name()));
        long elapsedMs = elapsedMs(started);

        assertThat(result.status()).isEqualTo(PgStatus.UNKNOWN);
        assertThat(result.detail()).isNotBlank();
        assertThat(elapsedMs).isBetween(2_800L, 4_500L);
        PgResult settled = awaitSettled(orderId);
        assertThat(settled.status()).isEqualTo(PgStatus.APPROVED);
        assertThat(settled.pgTxId()).isNotBlank();
    }

    @Test
    @DisplayName("처리 중인 주문을 취소해 PG가 409로 답하면 CONFLICT이고 주문은 그대로 PROCESSING이다")
    void cancelWhileProcessingConflicts() {
        String orderId = newOrderId();
        jdbcTemplate.update("INSERT INTO fake_pg_transaction (order_id, amount, status, pg_tx_id, created_at, "
                + "updated_at) VALUES (?, ?, 'PROCESSING', NULL, NOW(6), NOW(6))", orderId, AMOUNT);

        assertThat(pgClient.cancel(orderId)).isEqualTo(PgResult.of(PgStatus.CONFLICT, null));
        assertThat(pgClient.query(orderId).status()).isEqualTo(PgStatus.PROCESSING);
    }

    @Test
    @DisplayName("PG 포트가 닫혀 있으면 예외 없이 연결 제한 시간 안에 UNKNOWN이다")
    void unreachablePortIsUnknown() throws IOException {
        int unusedPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            unusedPort = socket.getLocalPort();
        }
        PgClient client = clientFor("http://127.0.0.1:" + unusedPort + "/fake-pg");

        long started = System.nanoTime();
        PgResult approve = client.approve(newOrderId(), AMOUNT, Optional.of(PgScenario.SUCCESS.name()));
        long elapsedMs = elapsedMs(started);

        assertThat(approve.status()).isEqualTo(PgStatus.UNKNOWN);
        assertThat(approve.detail()).isNotBlank();
        assertThat(elapsedMs).isLessThan(properties.pg().connectTimeout().toMillis() + 500);
        assertThat(client.query(newOrderId()).status()).isEqualTo(PgStatus.UNKNOWN);
        assertThat(client.cancel(newOrderId()).status()).isEqualTo(PgStatus.UNKNOWN);
    }

    private PgClient clientFor(String baseUrl) {
        return services.httpPgClient(withPg(baseUrl), environment);
    }

    private TicketingProperties withPg(String baseUrl) {
        return TestProperties.from(properties).pgBaseUrl(baseUrl).build();
    }

    // 서버 쪽 지연(slow-delay-ms)이 끝나 행이 확정될 때까지 기다려 다음 테스트에 섞이지 않게 한다
    private PgResult awaitSettled(String orderId) {
        return Await.until(properties.fakePg().slowDelay().plusSeconds(5), Duration.ofMillis(100),
                () -> pgClient.query(orderId), r -> r.status() != PgStatus.PROCESSING);
    }

    private static long elapsedMs(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000;
    }

    private static String newOrderId() {
        return "pgc-" + UUID.randomUUID().toString().substring(0, 18);
    }
}
