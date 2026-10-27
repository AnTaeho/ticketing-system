package com.example.ticketing.booking.payment.pg;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.ticketing.booking.BookingProperties;
import com.example.ticketing.booking.support.BookingHttpTest;
import java.io.IOException;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.mock.env.MockEnvironment;
import tools.jackson.databind.json.JsonMapper;

@BookingHttpTest
@Import(OddPgTestConfig.class)
class HttpPgClientTest {

    private static final int AMOUNT = 77_000;

    @Autowired
    private PgClient pgClient;
    @Autowired
    private BookingProperties properties;
    @Autowired
    private Environment environment;
    @Autowired
    private JsonMapper jsonMapper;

    @Test
    @DisplayName("base-url이 비어 있어도 실행 중 포트로 Fake PG에 닿는다. SUCCESS → APPROVED + pgTxId, 조회도 APPROVED")
    void approveSuccess() {
        assertThat(properties.pg().baseUrl()).isEmpty();
        String orderId = newOrderId();

        PgResult approved = pgClient.approve(orderId, AMOUNT, Optional.of(PgScenario.SUCCESS));

        assertThat(approved.status()).isEqualTo(PgStatus.APPROVED);
        assertThat(approved.pgTxId()).isNotBlank();
        assertThat(approved.detail()).isNull();
        assertThat(pgClient.query(orderId)).isEqualTo(PgResult.of(PgStatus.APPROVED, approved.pgTxId()));
    }

    @Test
    @DisplayName("DECLINE → DECLINED, pgTxId 없음")
    void approveDecline() {
        PgResult declined = pgClient.approve(newOrderId(), AMOUNT, Optional.of(PgScenario.DECLINE));

        assertThat(declined).isEqualTo(PgResult.of(PgStatus.DECLINED, null));
    }

    @Test
    @DisplayName("같은 orderId로 다시 승인하면 같은 결과를 돌려준다")
    void repeatedApproveReturnsSameResult() {
        String approvedOrder = newOrderId();
        PgResult first = pgClient.approve(approvedOrder, AMOUNT, Optional.of(PgScenario.SUCCESS));
        assertThat(pgClient.approve(approvedOrder, AMOUNT, Optional.of(PgScenario.SUCCESS))).isEqualTo(first);
        assertThat(pgClient.approve(approvedOrder, AMOUNT, Optional.empty())).isEqualTo(first);

        String declinedOrder = newOrderId();
        PgResult declined = pgClient.approve(declinedOrder, AMOUNT, Optional.of(PgScenario.DECLINE));
        assertThat(pgClient.approve(declinedOrder, AMOUNT, Optional.of(PgScenario.SUCCESS))).isEqualTo(declined);
    }

    @Test
    @DisplayName("SLOW_SUCCESS → read timeout(3초)에 걸려 UNKNOWN. 서버 쪽 승인이 끝나면 조회는 APPROVED")
    void slowSuccessTimesOutAsUnknownThenQueryApproved() {
        String orderId = newOrderId();

        long started = System.nanoTime();
        PgResult result = pgClient.approve(orderId, AMOUNT, Optional.of(PgScenario.SLOW_SUCCESS));
        long elapsedMs = elapsedMs(started);
        System.out.println("[HttpPgClientTest] SLOW_SUCCESS approve elapsedMs=" + elapsedMs + " detail=" + result.detail());

        assertThat(result.status()).isEqualTo(PgStatus.UNKNOWN);
        assertThat(result.detail()).isNotBlank();
        assertThat(elapsedMs).isBetween(2_800L, 4_500L);
        PgResult settled = awaitSettled(() -> pgClient.query(orderId));
        assertThat(settled.status()).isEqualTo(PgStatus.APPROVED);
        assertThat(settled.pgTxId()).isNotBlank();
    }

    @Test
    @DisplayName("조회: 없는 주문 → NOT_FOUND")
    void queryMissing() {
        assertThat(pgClient.query(newOrderId())).isEqualTo(PgResult.of(PgStatus.NOT_FOUND, null));
    }

    @Test
    @DisplayName("취소: 없는 주문 → CANCELLED(묘비), 그 뒤 승인은 DECLINED")
    void cancelMissingLeavesTombstone() {
        String orderId = newOrderId();

        assertThat(pgClient.cancel(orderId)).isEqualTo(PgResult.of(PgStatus.CANCELLED, null));
        assertThat(pgClient.query(orderId).status()).isEqualTo(PgStatus.CANCELLED);
        assertThat(pgClient.approve(orderId, AMOUNT, Optional.of(PgScenario.SUCCESS)).status())
                .isEqualTo(PgStatus.DECLINED);
    }

    @Test
    @DisplayName("취소: 승인된 주문 → CANCELLED, pgTxId 유지")
    void cancelApproved() {
        String orderId = newOrderId();
        PgResult approved = pgClient.approve(orderId, AMOUNT, Optional.of(PgScenario.SUCCESS));

        assertThat(pgClient.cancel(orderId)).isEqualTo(PgResult.of(PgStatus.CANCELLED, approved.pgTxId()));
    }

    @Test
    @DisplayName("취소: PROCESSING 중 → CONFLICT. 승인이 끝나면 APPROVED")
    void cancelWhileProcessingConflicts() throws Exception {
        String orderId = newOrderId();
        CompletableFuture<PgResult> slowApprove = CompletableFuture.supplyAsync(
                () -> pgClient.approve(orderId, AMOUNT, Optional.of(PgScenario.SLOW_SUCCESS)));
        try {
            assertThat(awaitStatus(() -> pgClient.query(orderId), PgStatus.PROCESSING).status())
                    .isEqualTo(PgStatus.PROCESSING);

            assertThat(pgClient.cancel(orderId)).isEqualTo(PgResult.of(PgStatus.CONFLICT, null));
        } finally {
            assertThat(slowApprove.get(10, TimeUnit.SECONDS).status()).isEqualTo(PgStatus.UNKNOWN);
            assertThat(awaitSettled(() -> pgClient.query(orderId)).status()).isEqualTo(PgStatus.APPROVED);
        }
    }

    @Test
    @DisplayName("닫힌 포트 → 예외 없이 UNKNOWN, connect timeout 안에 돌아온다")
    void closedPortIsUnknown() throws IOException {
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }
        PgClient client = clientFor("http://127.0.0.1:" + closedPort + "/fake-pg");

        long started = System.nanoTime();
        PgResult approve = client.approve(newOrderId(), AMOUNT, Optional.of(PgScenario.SUCCESS));
        long elapsedMs = elapsedMs(started);

        assertThat(approve.status()).isEqualTo(PgStatus.UNKNOWN);
        assertThat(approve.detail()).isNotBlank();
        assertThat(elapsedMs).isLessThan(properties.pg().connectTimeout().toMillis() + 500);
        assertThat(client.query(newOrderId()).status()).isEqualTo(PgStatus.UNKNOWN);
        assertThat(client.cancel(newOrderId()).status()).isEqualTo(PgStatus.UNKNOWN);
    }

    @Test
    @DisplayName("base-url도 local.server.port도 없으면 호출하지 않고 UNKNOWN")
    void noBaseUrlIsUnknown() {
        PgClient client = new HttpPgClient(withPg(""), new MockEnvironment(), jsonMapper);

        PgResult result = client.query(newOrderId());

        assertThat(result.status()).isEqualTo(PgStatus.UNKNOWN);
        assertThat(result.detail()).contains("local.server.port");
    }

    @ParameterizedTest(name = "{0}: 승인 {1}, 조회 {2}, 취소 {3}")
    @DisplayName("예상 밖 응답은 본문 status보다 HTTP 상태가 먼저다. 확정할 수 없으면 UNKNOWN")
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
        return new HttpPgClient(withPg(baseUrl), environment, jsonMapper);
    }

    private BookingProperties withPg(String baseUrl) {
        BookingProperties.Pg pg = properties.pg();
        return new BookingProperties(properties.seed(), properties.redis(), properties.hold(), properties.payment(),
                properties.gate(), properties.queue(), properties.seatmap(), properties.outbox(), properties.ops(),
                new BookingProperties.Pg(baseUrl, pg.connectTimeout(), pg.readTimeout()), properties.fakePg());
    }

    // 서버 쪽 지연(slow-delay-ms)이 끝나 행이 확정될 때까지 기다려 다음 테스트에 섞이지 않게 한다
    private PgResult awaitSettled(Supplier<PgResult> query) {
        Duration limit = Duration.ofMillis(properties.fakePg().slowDelayMs()).plusSeconds(5);
        long deadline = System.nanoTime() + limit.toNanos();
        PgResult last = query.get();
        while (last.is(PgStatus.PROCESSING) && System.nanoTime() < deadline) {
            pause();
            last = query.get();
        }
        return last;
    }

    private PgResult awaitStatus(Supplier<PgResult> query, PgStatus expected) {
        long deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
        PgResult last = query.get();
        while (!last.is(expected) && System.nanoTime() < deadline) {
            pause();
            last = query.get();
        }
        return last;
    }

    private static void pause() {
        try {
            Thread.sleep(100);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private static long elapsedMs(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000;
    }

    private static String newOrderId() {
        return "pgc-" + UUID.randomUUID().toString().substring(0, 18);
    }
}
