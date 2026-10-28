package com.example.ticketing.payment.controller;

import static com.example.ticketing.support.TestMvc.USER;
import static org.assertj.core.api.Assertions.assertThat;

import com.example.ticketing.common.config.TicketingProperties;
import com.example.ticketing.payment.client.HttpPgClient;
import com.example.ticketing.payment.client.OddPgTestConfig;
import com.example.ticketing.payment.client.PgClient;
import com.example.ticketing.payment.client.PgStatus;
import com.example.ticketing.payment.dto.PaymentStart.Started;
import com.example.ticketing.payment.service.PaymentTransaction;
import com.example.ticketing.support.Await;
import com.example.ticketing.support.Fixtures;
import com.example.ticketing.support.Fixtures.Held;
import com.example.ticketing.support.HttpIntegrationTest;
import com.example.ticketing.support.TestDb;
import com.example.ticketing.support.TestServices;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

// HttpPgClientTest와 같은 구성(RANDOM_PORT + OddPgTestConfig)이라 컨텍스트를 같이 쓴다
@HttpIntegrationTest
@Import(OddPgTestConfig.class)
class PaymentHttpTest {

    @Autowired
    private PgClient pgClient;
    @Autowired
    private PaymentTransaction paymentTransaction;
    @Autowired
    private Fixtures fixtures;
    @Autowired
    private TestDb db;
    @Autowired
    private TestServices services;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private Environment environment;
    @Autowired
    private JsonMapper jsonMapper;
    @Autowired
    private TicketingProperties properties;

    @Test
    @DisplayName("결제 API를 실제 HTTP로 부르면 Fake PG 승인을 거쳐 200 CONFIRMED다")
    void payOverHttpWithFakePg() {
        assertThat(pgClient).isInstanceOf(HttpPgClient.class);
        long pid = fixtures.newPerformance(1, 1, 4);
        Held held = fixtures.held(pid, db.seatIds(pid).subList(0, 2));

        ResponseEntity<String> response = client().post()
                .uri("/api/holds/{holdId}/payments", held.holdId())
                .header(USER, String.valueOf(held.userId()))
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .header("X-Fake-Pg-Scenario", "SUCCESS")
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"method\":\"CARD\"}")
                .retrieve()
                .toEntity(String.class);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        JsonNode body = jsonMapper.readTree(response.getBody());
        assertThat(body.get("status").asString()).isEqualTo("CONFIRMED");
        long paymentId = body.get("paymentId").asLong();
        String orderId = (String) db.payment(paymentId).get("order_id");
        assertThat(fakePgStatus(orderId)).isEqualTo("APPROVED");
        assertThat(db.payment(paymentId).get("pg_tx_id")).isEqualTo(fakePgTxId(orderId));
        assertThat(db.holdStatus(held.holdId())).isEqualTo("CONFIRMED");
        assertThat(db.bookingCountOfHold(held.holdId())).isEqualTo(1);
    }

    @Test
    @DisplayName("느린 승인 결제 → 202 UNKNOWN → 대사 → 조회 흐름에서 결제가 APPROVED로 확정된다")
    void slowSuccessIsConfirmedByReconcile() {
        assertThat(pgClient).isInstanceOf(HttpPgClient.class);
        long pid = fixtures.newPerformance(1, 1, 4);
        Held held = fixtures.held(pid, db.seatIds(pid).subList(0, 2));
        RestClient client = client();

        ResponseEntity<String> response = client.post()
                .uri("/api/holds/{holdId}/payments", held.holdId())
                .header(USER, String.valueOf(held.userId()))
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .header("X-Fake-Pg-Scenario", "SLOW_SUCCESS")
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"method\":\"CARD\"}")
                .retrieve()
                .toEntity(String.class);

        assertThat(response.getStatusCode().value()).isEqualTo(202);
        JsonNode body = jsonMapper.readTree(response.getBody());
        assertThat(body.get("status").asString()).isEqualTo("UNKNOWN");
        long paymentId = body.get("paymentId").asLong();
        String orderId = (String) db.payment(paymentId).get("order_id");
        assertThat(awaitFakePgSettled(orderId)).isEqualTo("APPROVED");

        db.parkUnsettledPayments();
        db.makePaymentDue(paymentId);
        services.paymentReconcileScheduler().run();

        ResponseEntity<String> view = client.get()
                .uri("/api/payments/{paymentId}", paymentId)
                .header(USER, String.valueOf(held.userId()))
                .retrieve()
                .toEntity(String.class);

        JsonNode viewBody = jsonMapper.readTree(view.getBody());
        assertThat(view.getStatusCode().value()).isEqualTo(200);
        assertThat(viewBody.get("status").asString()).isEqualTo("APPROVED");
        assertThat(viewBody.get("holdStatus").asString()).isEqualTo("CONFIRMED");
        assertThat(viewBody.get("bookingNumber").asString()).matches("B\\d{8}-\\d{6,}");
        assertThat(db.payment(paymentId).get("pg_tx_id")).isEqualTo(fakePgTxId(orderId));
    }

    @Test
    @DisplayName("PG 호출 전에 멈춘 고아 결제를 대사가 망취소하면 Fake PG가 뒤늦은 승인을 거절한다")
    void orphanLeavesTombstone() {
        long pid = fixtures.newPerformance(1, 1, 4);
        Held held = fixtures.held(pid, db.seatIds(pid).subList(0, 1));
        Started started = (Started) paymentTransaction.start(held.userId(), held.holdId(),
                UUID.randomUUID().toString());
        db.parkUnsettledPayments();
        db.agePayment(started.paymentId(), 60);
        db.makePaymentDue(started.paymentId());

        services.paymentReconcileScheduler().run();

        assertThat(jdbcTemplate.queryForList("SELECT status, amount FROM fake_pg_transaction WHERE order_id = ?",
                started.orderId())).singleElement()
                .satisfies(row -> assertThat(row).containsEntry("status", "CANCELLED").containsEntry("amount", 0));
        assertThat(db.payment(started.paymentId())).containsEntry("status", "CANCELLED");
        assertThat(db.holdStatus(held.holdId())).isEqualTo("RELEASED");
        assertThat(pgClient.approve(started.orderId(), started.amount(), Optional.empty()).status())
                .isEqualTo(PgStatus.DECLINED);
    }

    private RestClient client() {
        return RestClient.create("http://localhost:" + environment.getProperty("local.server.port"));
    }

    // 서버 쪽 SLOW_SUCCESS 지연(5초)이 끝나 Fake PG 행이 결론 날 때까지 기다린다
    private String awaitFakePgSettled(String orderId) {
        return Await.until(properties.fakePg().slowDelay().plusSeconds(5), Duration.ofMillis(100),
                () -> fakePgStatus(orderId), status -> !"PROCESSING".equals(status));
    }

    private String fakePgStatus(String orderId) {
        return jdbcTemplate.queryForObject("SELECT status FROM fake_pg_transaction WHERE order_id = ?",
                String.class, orderId);
    }

    private String fakePgTxId(String orderId) {
        return jdbcTemplate.queryForObject("SELECT pg_tx_id FROM fake_pg_transaction WHERE order_id = ?",
                String.class, orderId);
    }
}
