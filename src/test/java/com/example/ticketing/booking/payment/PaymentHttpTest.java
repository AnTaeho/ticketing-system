package com.example.ticketing.booking.payment;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.ticketing.booking.hold.SeatHoldService;
import com.example.ticketing.booking.hold.dto.HoldCreated;
import com.example.ticketing.booking.payment.pg.OddPgTestConfig;
import com.example.ticketing.booking.payment.pg.PgClient;
import com.example.ticketing.booking.payment.pg.HttpPgClient;
import com.example.ticketing.booking.support.BookingFixtures;
import com.example.ticketing.booking.support.BookingHttpTest;
import com.example.ticketing.booking.support.RedisKeyCleaner;
import java.util.List;
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
}
