package com.example.ticketing.booking.payment.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.ticketing.booking.hold.SeatHoldService;
import com.example.ticketing.booking.hold.dto.HoldCreated;
import com.example.ticketing.booking.payment.BookingPaymentTransaction;
import com.example.ticketing.booking.payment.PaymentDb;
import com.example.ticketing.booking.payment.StartResult.Started;
import com.example.ticketing.booking.payment.StubPgClient;
import com.example.ticketing.booking.payment.StubPgTestConfig;
import com.example.ticketing.booking.payment.pg.PgResult;
import com.example.ticketing.booking.payment.pg.PgScenario;
import com.example.ticketing.booking.payment.pg.PgStatus;
import com.example.ticketing.booking.support.BookingFixtures;
import com.example.ticketing.booking.support.BookingIntegrationTest;
import com.example.ticketing.booking.support.RedisKeyCleaner;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@BookingIntegrationTest
@Import(StubPgTestConfig.class)
class PaymentApiControllerTest {

    private static final String USER = "X-User-Id";
    private static final String KEY = "Idempotency-Key";
    private static final String SCENARIO = "X-Fake-Pg-Scenario";

    @Autowired
    private WebApplicationContext context;
    @Autowired
    private SeatHoldService seatHoldService;
    @Autowired
    private BookingPaymentTransaction paymentTransaction;
    @Autowired
    private StubPgClient stubPg;
    @Autowired
    private BookingFixtures fixtures;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private RedisKeyCleaner redisKeyCleaner;
    @Autowired
    private JsonMapper jsonMapper;

    private final List<Long> performances = new ArrayList<>();
    private MockMvc mockMvc;
    private PaymentDb db;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).build();
        stubPg.reset();
        db = new PaymentDb(jdbcTemplate);
    }

    @AfterEach
    void tearDown() {
        stubPg.reset();
        performances.forEach(redisKeyCleaner::clean);
    }

    @Test
    @DisplayName("Idempotency-Key가 없으면 400 IDEMPOTENCY_KEY_REQUIRED, 결제 행 없음")
    void missingIdempotencyKey() throws Exception {
        Hold h = newHold();

        mockMvc.perform(post("/api/holds/{holdId}/payments", h.holdId).header(USER, h.userId)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"method\":\"CARD\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REQUIRED"));

        assertThat(db.paymentCountOfHold(h.holdId)).isZero();
        assertThat(stubPg.approveCalls()).isZero();
    }

    @Test
    @DisplayName("200 CONFIRMED {paymentId, status, bookingId, bookingNumber}. 시나리오 헤더는 PgScenario로 넘어간다. "
            + "GET 결제·GET hold에도 반영")
    void confirmed() throws Exception {
        Hold h = newHold();

        JsonNode body = body(mockMvc.perform(payRequest(h, UUID.randomUUID().toString()).header(SCENARIO, "SUCCESS"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paymentId").isNumber())
                .andExpect(jsonPath("$.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.bookingId").isNumber())
                .andExpect(jsonPath("$.bookingNumber").value(org.hamcrest.Matchers.matchesPattern("B\\d{8}-\\d{6,}")))
                .andReturn());
        System.out.println("[S4.3] POST /payments 200 응답: " + body);
        long paymentId = body.get("paymentId").asLong();
        assertThat(stubPg.approvals()).singleElement()
                .satisfies(call -> assertThat(call.scenario()).isEqualTo(Optional.of(PgScenario.SUCCESS)));

        JsonNode view = body(mockMvc.perform(get("/api/payments/{paymentId}", paymentId).header(USER, h.userId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paymentId").value(paymentId))
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.holdStatus").value("CONFIRMED"))
                .andExpect(jsonPath("$.bookingId").value(body.get("bookingId").asLong()))
                .andExpect(jsonPath("$.bookingNumber").value(body.get("bookingNumber").asString()))
                .andReturn());
        System.out.println("[S4.3] GET /payments/{id} 응답: " + view);

        mockMvc.perform(get("/api/holds/{holdId}", h.holdId).header(USER, h.userId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.paymentId").value(paymentId))
                .andExpect(jsonPath("$.payDeadline").isString());
    }

    @Test
    @DisplayName("202 UNKNOWN {paymentId, status}만 있고 예매 정보 키는 없다. GET 결제는 UNKNOWN·PAYING")
    void acceptedUnknown() throws Exception {
        Hold h = newHold();
        stubPg.approveWith(PgResult.unknown("timeout"));

        JsonNode body = body(pay(h, UUID.randomUUID().toString())
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.paymentId").isNumber())
                .andExpect(jsonPath("$.status").value("UNKNOWN"))
                .andExpect(jsonPath("$.bookingId").doesNotExist())
                .andExpect(jsonPath("$.bookingNumber").doesNotExist())
                .andReturn());
        System.out.println("[S4.3] POST /payments 202 응답: " + body);
        long paymentId = body.get("paymentId").asLong();
        assertThat(stubPg.approvals()).singleElement()
                .satisfies(call -> assertThat(call.scenario()).isEmpty());

        mockMvc.perform(get("/api/payments/{paymentId}", paymentId).header(USER, h.userId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UNKNOWN"))
                .andExpect(jsonPath("$.holdStatus").value("PAYING"))
                .andExpect(jsonPath("$.bookingId").doesNotExist());
        mockMvc.perform(get("/api/holds/{holdId}", h.holdId).header(USER, h.userId))
                .andExpect(jsonPath("$.status").value("PAYING"))
                .andExpect(jsonPath("$.paymentId").value(paymentId))
                .andExpect(jsonPath("$.payDeadline").isString());
    }

    @Test
    @DisplayName("결제 시작만 된 결제에 같은 키로 다시 보내면 202 PROCESSING")
    void acceptedProcessing() throws Exception {
        Hold h = newHold();
        String key = UUID.randomUUID().toString();
        Started started = (Started) paymentTransaction.start(h.userId, h.holdId, key);

        pay(h, key)
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.paymentId").value(started.paymentId()))
                .andExpect(jsonPath("$.status").value("PROCESSING"));
        assertThat(stubPg.approveCalls()).isZero();
    }

    @Test
    @DisplayName("거절은 402 PAYMENT_DECLINED + detail.paymentId. 거절로 풀린 hold에 다른 키로 보내면 409 HOLD_NOT_PAYABLE(detail.status RELEASED)")
    void declined() throws Exception {
        Hold h = newHold();
        stubPg.approveWith(PgResult.of(PgStatus.DECLINED, null));

        JsonNode body = body(pay(h, UUID.randomUUID().toString())
                .andExpect(status().isPaymentRequired())
                .andExpect(jsonPath("$.code").value("PAYMENT_DECLINED"))
                .andExpect(jsonPath("$.message").isString())
                .andExpect(jsonPath("$.detail.paymentId").isNumber())
                .andReturn());
        System.out.println("[S4.3] POST /payments 402 응답: " + body);
        long paymentId = body.get("detail").get("paymentId").asLong();
        assertThat(db.payment(paymentId)).containsEntry("status", "DECLINED");

        pay(h, UUID.randomUUID().toString())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("HOLD_NOT_PAYABLE"))
                .andExpect(jsonPath("$.detail.status").value("RELEASED"));
    }

    @Test
    @DisplayName("다른 키 동시 결제 → 409 ALREADY_PAYING, 만료 → 409 HOLD_EXPIRED")
    void conflicts() throws Exception {
        Hold paying = newHold();
        stubPg.approveWith(PgResult.unknown("timeout"));
        pay(paying, UUID.randomUUID().toString()).andExpect(status().isAccepted());
        pay(paying, UUID.randomUUID().toString())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ALREADY_PAYING"));

        Hold expired = newHold();
        db.expireHold(expired.holdId);
        pay(expired, UUID.randomUUID().toString())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("HOLD_EXPIRED"));
    }

    @Test
    @DisplayName("남의 hold 결제·남의 결제 조회는 404, 모르는 시나리오 헤더는 400 INVALID_REQUEST")
    void notFoundAndBadScenario() throws Exception {
        Hold h = newHold();
        long stranger = fixtures.newUser();

        mockMvc.perform(post("/api/holds/{holdId}/payments", h.holdId).header(USER, stranger)
                        .header(KEY, UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"method\":\"CARD\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("HOLD_NOT_FOUND"));

        mockMvc.perform(payRequest(h, UUID.randomUUID().toString()).header(SCENARIO, "NOPE"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.detail.header").value(SCENARIO));
        assertThat(db.paymentCountOfHold(h.holdId)).isZero();

        long paymentId = body(pay(h, UUID.randomUUID().toString()).andExpect(status().isOk()).andReturn())
                .get("paymentId").asLong();
        mockMvc.perform(get("/api/payments/{paymentId}", paymentId).header(USER, stranger))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PAYMENT_NOT_FOUND"));
        mockMvc.perform(get("/api/payments/{paymentId}", Long.MAX_VALUE).header(USER, h.userId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PAYMENT_NOT_FOUND"));
    }

    private ResultActions pay(Hold h, String key) throws Exception {
        return mockMvc.perform(payRequest(h, key));
    }

    private MockHttpServletRequestBuilder payRequest(Hold h, String key) {
        return post("/api/holds/{holdId}/payments", h.holdId)
                .header(USER, h.userId)
                .header(KEY, key)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"method\":\"CARD\"}");
    }

    private Hold newHold() {
        long pid = fixtures.newPerformance(1, 1, 4);
        performances.add(pid);
        long userId = fixtures.newUser();
        HoldCreated hold = seatHoldService.hold(userId, pid, db.seatIds(pid).subList(0, 2), null);
        return new Hold(userId, hold.holdId());
    }

    private JsonNode body(MvcResult result) throws Exception {
        return jsonMapper.readTree(result.getResponse().getContentAsString());
    }

    private record Hold(long userId, long holdId) {
    }
}
