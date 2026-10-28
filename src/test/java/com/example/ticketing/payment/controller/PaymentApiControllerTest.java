package com.example.ticketing.payment.controller;

import static com.example.ticketing.support.TestMvc.USER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.ticketing.payment.client.PgResult;
import com.example.ticketing.payment.client.PgStatus;
import com.example.ticketing.payment.dto.PaymentStart.Started;
import com.example.ticketing.payment.dto.PgScenario;
import com.example.ticketing.payment.service.PaymentTransaction;
import com.example.ticketing.support.Fixtures;
import com.example.ticketing.support.Fixtures.Held;
import com.example.ticketing.support.IntegrationTest;
import com.example.ticketing.support.StubPgClient;
import com.example.ticketing.support.StubPgTestConfig;
import com.example.ticketing.support.TestDb;
import com.example.ticketing.support.TestMvc;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;

@IntegrationTest
@Import(StubPgTestConfig.class)
class PaymentApiControllerTest {

    private static final String KEY = "Idempotency-Key";
    private static final String SCENARIO = "X-Fake-Pg-Scenario";

    @Autowired
    private PaymentTransaction paymentTransaction;
    @Autowired
    private StubPgClient stubPg;
    @Autowired
    private Fixtures fixtures;
    @Autowired
    private TestDb db;
    @Autowired
    private TestMvc testMvc;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = testMvc.mockMvc();
        stubPg.reset();
    }

    @AfterEach
    void tearDown() {
        stubPg.reset();
    }

    @Test
    @DisplayName("멱등키 헤더가 없으면 결제 행 없이 400으로 답한다")
    void missingIdempotencyKey() throws Exception {
        Held held = newHold();

        mockMvc.perform(post("/api/holds/{holdId}/payments", held.holdId()).header(USER, held.userId())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"method\":\"CARD\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REQUIRED"));

        assertThat(db.paymentCountOfHold(held.holdId())).isZero();
        assertThat(stubPg.approveCalls()).isZero();
    }

    @Test
    @DisplayName("멱등키가 비었거나 64자를 넘으면 결제 행 없이 400으로 답한다")
    void idempotencyKeyValidation() throws Exception {
        Held held = newHold();

        pay(held, "  ")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REQUIRED"));
        pay(held, "k".repeat(65))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.detail.header").value(KEY))
                .andExpect(jsonPath("$.detail.maxLength").value(64));

        assertThat(db.paymentCountOfHold(held.holdId())).isZero();
        assertThat(stubPg.approveCalls()).isZero();
    }

    @Test
    @DisplayName("결제가 승인되면 200 CONFIRMED로 답하고 결제·선점 조회에도 반영된다")
    void confirmed() throws Exception {
        Held held = newHold();

        JsonNode body = testMvc.body(mockMvc.perform(
                        payRequest(held, UUID.randomUUID().toString()).header(SCENARIO, "SUCCESS"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paymentId").isNumber())
                .andExpect(jsonPath("$.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.bookingId").isNumber())
                .andExpect(jsonPath("$.bookingNumber").value(org.hamcrest.Matchers.matchesPattern("B\\d{8}-\\d{6,}")))
                .andReturn());
        long paymentId = body.get("paymentId").asLong();
        assertThat(stubPg.approvals()).singleElement()
                .satisfies(call -> assertThat(call.scenario()).isEqualTo(Optional.of(PgScenario.SUCCESS.name())));

        mockMvc.perform(get("/api/payments/{paymentId}", paymentId).header(USER, held.userId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paymentId").value(paymentId))
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.holdStatus").value("CONFIRMED"))
                .andExpect(jsonPath("$.bookingId").value(body.get("bookingId").asLong()))
                .andExpect(jsonPath("$.bookingNumber").value(body.get("bookingNumber").asString()));

        mockMvc.perform(get("/api/holds/{holdId}", held.holdId()).header(USER, held.userId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.paymentId").value(paymentId))
                .andExpect(jsonPath("$.payDeadline").isString());
    }

    @Test
    @DisplayName("결제 결과를 모르면 202 UNKNOWN으로 답하고 예매 정보는 주지 않는다")
    void acceptedUnknown() throws Exception {
        Held held = newHold();
        stubPg.approveWith(PgResult.unknown("timeout"));

        JsonNode body = testMvc.body(pay(held, UUID.randomUUID().toString())
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.paymentId").isNumber())
                .andExpect(jsonPath("$.status").value("UNKNOWN"))
                .andExpect(jsonPath("$.bookingId").doesNotExist())
                .andExpect(jsonPath("$.bookingNumber").doesNotExist())
                .andReturn());
        long paymentId = body.get("paymentId").asLong();
        assertThat(stubPg.approvals()).singleElement()
                .satisfies(call -> assertThat(call.scenario()).isEmpty());

        mockMvc.perform(get("/api/payments/{paymentId}", paymentId).header(USER, held.userId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UNKNOWN"))
                .andExpect(jsonPath("$.holdStatus").value("PAYING"))
                .andExpect(jsonPath("$.bookingId").doesNotExist());
        mockMvc.perform(get("/api/holds/{holdId}", held.holdId()).header(USER, held.userId()))
                .andExpect(jsonPath("$.status").value("PAYING"))
                .andExpect(jsonPath("$.paymentId").value(paymentId))
                .andExpect(jsonPath("$.payDeadline").isString());
    }

    @Test
    @DisplayName("결제 시작만 된 결제에 같은 키로 다시 보내면 202 PROCESSING이다")
    void acceptedProcessing() throws Exception {
        Held held = newHold();
        String key = UUID.randomUUID().toString();
        Started started = (Started) paymentTransaction.start(held.userId(), held.holdId(), key);

        pay(held, key)
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.paymentId").value(started.paymentId()))
                .andExpect(jsonPath("$.status").value("PROCESSING"));
        assertThat(stubPg.approveCalls()).isZero();
    }

    @Test
    @DisplayName("결제가 거절되면 402로 답하고 풀린 선점에 다시 보내면 409다")
    void declined() throws Exception {
        Held held = newHold();
        stubPg.approveWith(PgResult.of(PgStatus.DECLINED, null));

        JsonNode body = testMvc.body(pay(held, UUID.randomUUID().toString())
                .andExpect(status().isPaymentRequired())
                .andExpect(jsonPath("$.code").value("PAYMENT_DECLINED"))
                .andExpect(jsonPath("$.message").isString())
                .andExpect(jsonPath("$.detail.paymentId").isNumber())
                .andReturn());
        long paymentId = body.get("detail").get("paymentId").asLong();
        assertThat(db.payment(paymentId)).containsEntry("status", "DECLINED");

        pay(held, UUID.randomUUID().toString())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("HOLD_NOT_PAYABLE"))
                .andExpect(jsonPath("$.detail.status").value("RELEASED"));
    }

    @Test
    @DisplayName("다른 키로 결제 중이거나 만료된 선점에 보내면 409로 답한다")
    void conflicts() throws Exception {
        Held paying = newHold();
        stubPg.approveWith(PgResult.unknown("timeout"));
        pay(paying, UUID.randomUUID().toString()).andExpect(status().isAccepted());
        pay(paying, UUID.randomUUID().toString())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ALREADY_PAYING"));

        Held expired = newHold();
        db.expireHold(expired.holdId());
        pay(expired, UUID.randomUUID().toString())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("HOLD_EXPIRED"));
    }

    @Test
    @DisplayName("남의 선점·결제는 404, 모르는 시나리오 헤더는 400으로 답한다")
    void notFoundAndBadScenario() throws Exception {
        Held held = newHold();
        long stranger = fixtures.newUser();

        mockMvc.perform(post("/api/holds/{holdId}/payments", held.holdId()).header(USER, stranger)
                        .header(KEY, UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"method\":\"CARD\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("HOLD_NOT_FOUND"));

        mockMvc.perform(payRequest(held, UUID.randomUUID().toString()).header(SCENARIO, "NOPE"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.detail.header").value(SCENARIO));
        assertThat(db.paymentCountOfHold(held.holdId())).isZero();

        long paymentId = testMvc.body(pay(held, UUID.randomUUID().toString()).andExpect(status().isOk()).andReturn())
                .get("paymentId").asLong();
        mockMvc.perform(get("/api/payments/{paymentId}", paymentId).header(USER, stranger))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PAYMENT_NOT_FOUND"));
        mockMvc.perform(get("/api/payments/{paymentId}", Long.MAX_VALUE).header(USER, held.userId()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PAYMENT_NOT_FOUND"));
    }

    private ResultActions pay(Held held, String key) throws Exception {
        return mockMvc.perform(payRequest(held, key));
    }

    private MockHttpServletRequestBuilder payRequest(Held held, String key) {
        return post("/api/holds/{holdId}/payments", held.holdId())
                .header(USER, held.userId())
                .header(KEY, key)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"method\":\"CARD\"}");
    }

    // 1구역 1열 4석 회차에서 앞 2석을 잡은 상태
    private Held newHold() {
        long pid = fixtures.newPerformance(1, 1, 4);
        return fixtures.held(pid, db.seatIds(pid).subList(0, 2));
    }
}
