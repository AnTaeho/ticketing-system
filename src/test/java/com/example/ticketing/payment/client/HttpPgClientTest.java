package com.example.ticketing.payment.client;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.ticketing.common.config.TicketingProperties;
import com.example.ticketing.payment.dto.PgScenario;
import com.example.ticketing.support.Await;
import com.example.ticketing.support.Concurrently;
import com.example.ticketing.support.Concurrently.Results;
import com.example.ticketing.support.Concurrently.Running;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.mock.env.MockEnvironment;

@HttpIntegrationTest
@Import(OddPgTestConfig.class)
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
    @DisplayName("같은 주문 번호로 다시 승인하면 같은 결과를 돌려준다")
    void repeatedApproveReturnsSameResult() {
        String approvedOrder = newOrderId();
        PgResult first = pgClient.approve(approvedOrder, AMOUNT, Optional.of(PgScenario.SUCCESS.name()));
        assertThat(pgClient.approve(approvedOrder, AMOUNT, Optional.of(PgScenario.SUCCESS.name()))).isEqualTo(first);
        assertThat(pgClient.approve(approvedOrder, AMOUNT, Optional.empty())).isEqualTo(first);

        String declinedOrder = newOrderId();
        PgResult declined = pgClient.approve(declinedOrder, AMOUNT, Optional.of(PgScenario.DECLINE.name()));
        assertThat(pgClient.approve(declinedOrder, AMOUNT, Optional.of(PgScenario.SUCCESS.name()))).isEqualTo(declined);
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
    @DisplayName("없는 주문을 조회하면 NOT_FOUND다")
    void queryMissing() {
        assertThat(pgClient.query(newOrderId())).isEqualTo(PgResult.of(PgStatus.NOT_FOUND, null));
    }

    @Test
    @DisplayName("없는 주문을 취소하면 묘비가 남아 그 뒤 승인은 DECLINED다")
    void cancelMissingLeavesTombstone() {
        String orderId = newOrderId();

        assertThat(pgClient.cancel(orderId)).isEqualTo(PgResult.of(PgStatus.CANCELLED, null));
        assertThat(pgClient.query(orderId).status()).isEqualTo(PgStatus.CANCELLED);
        assertThat(pgClient.approve(orderId, AMOUNT, Optional.of(PgScenario.SUCCESS.name())).status())
                .isEqualTo(PgStatus.DECLINED);
    }

    @Test
    @DisplayName("승인된 주문을 취소하면 PG 거래 번호를 둔 채 CANCELLED다")
    void cancelApproved() {
        String orderId = newOrderId();
        PgResult approved = pgClient.approve(orderId, AMOUNT, Optional.of(PgScenario.SUCCESS.name()));

        assertThat(pgClient.cancel(orderId)).isEqualTo(PgResult.of(PgStatus.CANCELLED, approved.pgTxId()));
    }

    @Test
    @DisplayName("처리 중인 주문을 취소하면 CONFLICT이고 승인이 끝나면 APPROVED다")
    void cancelWhileProcessingConflicts() {
        String orderId = newOrderId();

        try (Running<PgResult> slowApprove = Concurrently.of(1).within(Duration.ofSeconds(10))
                .start(i -> pgClient.approve(orderId, AMOUNT, Optional.of(PgScenario.SLOW_SUCCESS.name())))) {
            PgResult processing = Await.until(Duration.ofSeconds(3), Duration.ofMillis(100),
                    () -> pgClient.query(orderId), r -> r.status() == PgStatus.PROCESSING);
            assertThat(processing.status()).isEqualTo(PgStatus.PROCESSING);

            assertThat(pgClient.cancel(orderId)).isEqualTo(PgResult.of(PgStatus.CONFLICT, null));

            Results<PgResult> approved = slowApprove.await();
            assertThat(approved.errors()).isEmpty();
            assertThat(approved.values()).singleElement()
                    .satisfies(r -> assertThat(r.status()).isEqualTo(PgStatus.UNKNOWN));
        } finally {
            assertThat(awaitSettled(orderId).status()).isEqualTo(PgStatus.APPROVED);
        }
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

    @Test
    @DisplayName("PG 주소도 실행 포트도 없으면 호출하지 않고 UNKNOWN이다")
    void noBaseUrlIsUnknown() {
        PgClient client = services.httpPgClient(withPg(""), new MockEnvironment());

        PgResult result = client.query(newOrderId());

        assertThat(result.status()).isEqualTo(PgStatus.UNKNOWN);
        assertThat(result.detail()).contains("local.server.port");
    }

    @ParameterizedTest(name = "{0}: 승인 {1}, 조회 {2}, 취소 {3}")
    @DisplayName("예상 밖 응답은 본문보다 HTTP 상태를 먼저 보고 확정할 수 없으면 UNKNOWN이다")
    @CsvSource({
            "server-error,            UNKNOWN,  UNKNOWN,   UNKNOWN",
            "not-json,                UNKNOWN,  UNKNOWN,   UNKNOWN",
            "empty,                   UNKNOWN,  UNKNOWN,   UNKNOWN",
            "unknown-status,          UNKNOWN,  UNKNOWN,   UNKNOWN",
            "not-found-status-on-200, UNKNOWN,  UNKNOWN,   UNKNOWN",
            "plain-404,               UNKNOWN,  UNKNOWN,   UNKNOWN",
            "conflict,                UNKNOWN,  UNKNOWN,   CONFLICT",
            "bad-request,             UNKNOWN,  UNKNOWN,   UNKNOWN",
            "ok,                      APPROVED, APPROVED,  APPROVED"
    })
    void oddResponses(String mode, PgStatus approve, PgStatus query, PgStatus cancel) {
        PgClient client = clientFor("http://localhost:" + environment.getProperty("local.server.port")
                + OddPgTestConfig.BASE + mode);

        PgResult approveResult = client.approve(newOrderId(), AMOUNT, Optional.empty());
        assertThat(approveResult.status()).isEqualTo(approve);
        assertThat(client.query(newOrderId()).status()).isEqualTo(query);
        assertThat(client.cancel(newOrderId()).status()).isEqualTo(cancel);
        if (approve == PgStatus.UNKNOWN) {
            assertThat(approveResult.detail()).isNotBlank();
            assertThat(approveResult.pgTxId()).isNull();
        }
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
