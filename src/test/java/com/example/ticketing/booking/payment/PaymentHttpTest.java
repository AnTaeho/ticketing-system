package com.example.ticketing.booking.payment;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.ticketing.booking.BookingProperties;
import com.example.ticketing.booking.hold.SeatHoldService;
import com.example.ticketing.booking.hold.dto.HoldCreated;
import com.example.ticketing.booking.hold.gate.RedisSeatGate;
import com.example.ticketing.booking.payment.StartResult.Started;
import com.example.ticketing.booking.payment.pg.OddPgTestConfig;
import com.example.ticketing.booking.payment.pg.PgClient;
import com.example.ticketing.booking.payment.pg.HttpPgClient;
import com.example.ticketing.booking.payment.pg.PgStatus;
import com.example.ticketing.booking.support.BookingFixtures;
import com.example.ticketing.booking.support.BookingHttpTest;
import com.example.ticketing.booking.support.RedisKeyCleaner;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.List;
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
@BookingHttpTest
@Import(OddPgTestConfig.class)
class PaymentHttpTest {

    @Autowired
    private PgClient pgClient;
    @Autowired
    private SeatHoldService seatHoldService;
    @Autowired
    private BookingFixtures fixtures;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private RedisKeyCleaner redisKeyCleaner;
    @Autowired
    private Environment environment;
    @Autowired
    private JsonMapper jsonMapper;
    @Autowired
    private PaymentReconcileTransaction reconcileTransaction;
    @Autowired
    private BookingPaymentTransaction paymentTransaction;
    @Autowired
    private RedisSeatGate gate;
    @Autowired
    private BookingProperties properties;
    @Autowired
    private MeterRegistry meterRegistry;

    @Test
    @DisplayName("실제 HTTP: 결제 API → HttpPgClient → Fake PG(SUCCESS) → 200 CONFIRMED, Fake PG 행 APPROVED")
    void payOverHttpWithFakePg() {
        assertThat(pgClient).isInstanceOf(HttpPgClient.class);
        long pid = fixtures.newPerformance(1, 1, 4);
        try {
            long userId = fixtures.newUser();
            List<Long> seats = jdbcTemplate.queryForList("SELECT id FROM seat WHERE performance_id = ? ORDER BY id",
                    Long.class, pid).subList(0, 2);
            HoldCreated hold = seatHoldService.hold(userId, pid, seats, null);
            RestClient client = RestClient.create("http://localhost:" + environment.getProperty("local.server.port"));

            ResponseEntity<String> response = client.post()
                    .uri("/api/holds/{holdId}/payments", hold.holdId())
                    .header("X-User-Id", String.valueOf(userId))
                    .header("Idempotency-Key", UUID.randomUUID().toString())
                    .header("X-Fake-Pg-Scenario", "SUCCESS")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body("{\"method\":\"CARD\"}")
                    .retrieve()
                    .toEntity(String.class);
            System.out.println("[S4.3] 실제 HTTP 결제 응답: " + response.getStatusCode() + " " + response.getBody());

            assertThat(response.getStatusCode().value()).isEqualTo(200);
            JsonNode body = jsonMapper.readTree(response.getBody());
            assertThat(body.get("status").asString()).isEqualTo("CONFIRMED");
            long paymentId = body.get("paymentId").asLong();
            String orderId = jdbcTemplate.queryForObject("SELECT order_id FROM booking_payment WHERE id = ?",
                    String.class, paymentId);
            assertThat(jdbcTemplate.queryForObject("SELECT status FROM fake_pg_transaction WHERE order_id = ?",
                    String.class, orderId)).isEqualTo("APPROVED");
            assertThat(jdbcTemplate.queryForObject("SELECT pg_tx_id FROM booking_payment WHERE id = ?",
                    String.class, paymentId)).isEqualTo(jdbcTemplate.queryForObject(
                    "SELECT pg_tx_id FROM fake_pg_transaction WHERE order_id = ?", String.class, orderId));
            assertThat(jdbcTemplate.queryForObject("SELECT status FROM seat_hold WHERE id = ?",
                    String.class, hold.holdId())).isEqualTo("CONFIRMED");
            assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM booking WHERE hold_id = ?",
                    Long.class, hold.holdId())).isEqualTo(1);
        } finally {
            redisKeyCleaner.clean(pid);
        }
    }

    @Test
    @DisplayName("실제 HTTP: SLOW_SUCCESS 결제 → 202 UNKNOWN → Fake PG 승인 완료 뒤 대사 → GET /api/payments/{id} APPROVED + 예매번호")
    void slowSuccessIsConfirmedByReconcile() throws Exception {
        assertThat(pgClient).isInstanceOf(HttpPgClient.class);
        long pid = fixtures.newPerformance(1, 1, 4);
        try {
            long userId = fixtures.newUser();
            HoldCreated hold = seatHoldService.hold(userId, pid, seatIds(pid).subList(0, 2), null);
            RestClient client = client();

            ResponseEntity<String> response = client.post()
                    .uri("/api/holds/{holdId}/payments", hold.holdId())
                    .header("X-User-Id", String.valueOf(userId))
                    .header("Idempotency-Key", UUID.randomUUID().toString())
                    .header("X-Fake-Pg-Scenario", "SLOW_SUCCESS")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body("{\"method\":\"CARD\"}")
                    .retrieve()
                    .toEntity(String.class);
            System.out.println("[S5.1] SLOW_SUCCESS 결제 응답: " + response.getStatusCode() + " " + response.getBody());
            assertThat(response.getStatusCode().value()).isEqualTo(202);
            JsonNode body = jsonMapper.readTree(response.getBody());
            assertThat(body.get("status").asString()).isEqualTo("UNKNOWN");
            long paymentId = body.get("paymentId").asLong();
            String orderId = orderIdOf(paymentId);
            assertThat(awaitFakePgSettled(orderId)).isEqualTo("APPROVED");

            PaymentDb db = new PaymentDb(jdbcTemplate);
            db.parkUnsettledPayments();
            db.makeDue(paymentId);
            reconcileScheduler().scan();

            ResponseEntity<String> view = client.get()
                    .uri("/api/payments/{paymentId}", paymentId)
                    .header("X-User-Id", String.valueOf(userId))
                    .retrieve()
                    .toEntity(String.class);
            System.out.println("[S5.1] 대사 뒤 결제 조회: " + view.getStatusCode() + " " + view.getBody());
            JsonNode viewBody = jsonMapper.readTree(view.getBody());
            assertThat(view.getStatusCode().value()).isEqualTo(200);
            assertThat(viewBody.get("status").asString()).isEqualTo("APPROVED");
            assertThat(viewBody.get("holdStatus").asString()).isEqualTo("CONFIRMED");
            assertThat(viewBody.get("bookingNumber").asString()).matches("B\\d{8}-\\d{6,}");
            assertThat(jdbcTemplate.queryForObject("SELECT pg_tx_id FROM booking_payment WHERE id = ?",
                    String.class, paymentId)).isEqualTo(jdbcTemplate.queryForObject(
                    "SELECT pg_tx_id FROM fake_pg_transaction WHERE order_id = ?", String.class, orderId));
        } finally {
            redisKeyCleaner.clean(pid);
        }
    }

    @Test
    @DisplayName("실제 HTTP: PG 호출 전에 멈춘 REQUESTED 고아를 생성 30초 뒤 대사가 망취소 → Fake PG 묘비 CANCELLED, 결제 CANCELLED, "
            + "hold RELEASED, 뒤늦은 승인은 DECLINED")
    void orphanLeavesTombstone() {
        long pid = fixtures.newPerformance(1, 1, 4);
        try {
            long userId = fixtures.newUser();
            HoldCreated hold = seatHoldService.hold(userId, pid, seatIds(pid).subList(0, 1), null);
            Started started = (Started) paymentTransaction.start(userId, hold.holdId(), UUID.randomUUID().toString());
            PaymentDb db = new PaymentDb(jdbcTemplate);
            db.parkUnsettledPayments();
            db.ageCreatedAt(started.paymentId(), 60);
            db.makeDue(started.paymentId());

            reconcileScheduler().scan();

            assertThat(jdbcTemplate.queryForList("SELECT status, amount FROM fake_pg_transaction WHERE order_id = ?",
                    started.orderId())).singleElement()
                    .satisfies(row -> assertThat(row).containsEntry("status", "CANCELLED").containsEntry("amount", 0));
            assertThat(db.payment(started.paymentId())).containsEntry("status", "CANCELLED");
            assertThat(db.holdStatus(hold.holdId())).isEqualTo("RELEASED");
            assertThat(pgClient.approve(started.orderId(), started.amount(), Optional.empty()).status())
                    .isEqualTo(PgStatus.DECLINED);
        } finally {
            redisKeyCleaner.clean(pid);
        }
    }

    private PaymentReconcileScheduler reconcileScheduler() {
        return new PaymentReconcileScheduler(reconcileTransaction, paymentTransaction, pgClient, gate, properties,
                meterRegistry);
    }

    private RestClient client() {
        return RestClient.create("http://localhost:" + environment.getProperty("local.server.port"));
    }

    private List<Long> seatIds(long pid) {
        return jdbcTemplate.queryForList("SELECT id FROM seat WHERE performance_id = ? ORDER BY id", Long.class, pid);
    }

    private String orderIdOf(long paymentId) {
        return jdbcTemplate.queryForObject("SELECT order_id FROM booking_payment WHERE id = ?", String.class, paymentId);
    }

    // 서버 쪽 SLOW_SUCCESS 지연(5초)이 끝나 Fake PG 행이 결론 날 때까지 기다린다
    private String awaitFakePgSettled(String orderId) throws InterruptedException {
        long deadline = System.nanoTime()
                + Duration.ofMillis(properties.fakePg().slowDelayMs()).plusSeconds(5).toNanos();
        String status = fakePgStatus(orderId);
        while ("PROCESSING".equals(status) && System.nanoTime() < deadline) {
            Thread.sleep(100);
            status = fakePgStatus(orderId);
        }
        return status;
    }

    private String fakePgStatus(String orderId) {
        return jdbcTemplate.queryForObject("SELECT status FROM fake_pg_transaction WHERE order_id = ?",
                String.class, orderId);
    }
}
