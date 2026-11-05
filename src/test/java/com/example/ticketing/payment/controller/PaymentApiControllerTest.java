package com.example.ticketing.payment.controller;

import static com.example.ticketing.common.web.HttpNames.USER_ID_HEADER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.ticketing.payment.client.PgResult;
import com.example.ticketing.payment.client.PgStatus;
import com.example.ticketing.payment.dto.PgScenario;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;

// 결제 판정은 PaymentServiceTest가 본다. 여기서는 헤더 해석과 결과별 HTTP 상태만 본다
@IntegrationTest
@Import(StubPgTestConfig.class)
class PaymentApiControllerTest {

    private static final String KEY = "Idempotency-Key";
    private static final String SCENARIO = "X-Fake-Pg-Scenario";

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
    @DisplayName("멱등키가 없거나 비었거나 64자를 넘거나 시나리오 헤더를 모르면 결제 행 없이 400으로 답한다")
    void invalidHeaders() throws Exception {
        Held held = newHold();

        mockMvc.perform(payRequest(held, null))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REQUIRED"));
        mockMvc.perform(payRequest(held, "  "))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REQUIRED"));
        mockMvc.perform(payRequest(held, "k".repeat(65)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.detail.header").value(KEY))
                .andExpect(jsonPath("$.detail.maxLength").value(64));
        mockMvc.perform(payRequest(held, key()).header(SCENARIO, "NOPE"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.detail.header").value(SCENARIO));

        assertThat(db.paymentCountOfHold(held.holdId())).isZero();
        assertThat(stubPg.approveCalls()).isZero();
    }

    @Test
    @DisplayName("결제가 승인되면 200 CONFIRMED로 답하고 결제·선점 조회에도 반영된다")
    void confirmed() throws Exception {
        Held held = newHold();

        JsonNode body = testMvc.body(mockMvc.perform(payRequest(held, key()).header(SCENARIO, "SUCCESS"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paymentId").isNumber())
                .andExpect(jsonPath("$.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.bookingId").isNumber())
                .andExpect(jsonPath("$.bookingNumber").value(matchesPattern("B\\d{8}-\\d{6,}")))
                .andReturn());
        long paymentId = body.get("paymentId").asLong();
        assertThat(stubPg.approvals()).singleElement()
                .satisfies(call -> assertThat(call.scenario()).isEqualTo(Optional.of(PgScenario.SUCCESS.name())));

        mockMvc.perform(get("/api/payments/{paymentId}", paymentId).header(USER_ID_HEADER, held.userId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paymentId").value(paymentId))
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.holdStatus").value("CONFIRMED"))
                .andExpect(jsonPath("$.bookingId").value(body.get("bookingId").asLong()))
                .andExpect(jsonPath("$.bookingNumber").value(body.get("bookingNumber").asString()));
        mockMvc.perform(get("/api/holds/{holdId}", held.holdId()).header(USER_ID_HEADER, held.userId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.paymentId").value(paymentId));
    }

    @Test
    @DisplayName("결제 결과를 모르면 202 UNKNOWN으로 답하고 예매 정보는 주지 않는다")
    void acceptedUnknown() throws Exception {
        Held held = newHold();
        stubPg.approveWith(PgResult.unknown("timeout"));

        mockMvc.perform(payRequest(held, key()))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.paymentId").isNumber())
                .andExpect(jsonPath("$.status").value("UNKNOWN"))
                .andExpect(jsonPath("$.bookingId").doesNotExist())
                .andExpect(jsonPath("$.bookingNumber").doesNotExist());
        assertThat(stubPg.approvals()).singleElement()
                .satisfies(call -> assertThat(call.scenario()).isEmpty());
    }

    @Test
    @DisplayName("결제가 거절되면 402 PAYMENT_DECLINED로 결제 번호를 담아 답한다")
    void declined() throws Exception {
        Held held = newHold();
        stubPg.approveWith(PgResult.of(PgStatus.DECLINED, null));

        JsonNode body = testMvc.body(mockMvc.perform(payRequest(held, key()))
                .andExpect(status().isPaymentRequired())
                .andExpect(jsonPath("$.code").value("PAYMENT_DECLINED"))
                .andExpect(jsonPath("$.message").isString())
                .andExpect(jsonPath("$.detail.paymentId").isNumber())
                .andReturn());

        assertThat(db.payment(body.get("detail").get("paymentId").asLong())).containsEntry("status", "DECLINED");
    }

    @Test
    @DisplayName("사용자 식별이 없으면 결제와 결제 조회 모두 401 LOGIN_REQUIRED로 답한다")
    void loginRequired() throws Exception {
        mockMvc.perform(post("/api/holds/{holdId}/payments", 1L).header(KEY, key())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"method\":\"CARD\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("LOGIN_REQUIRED"));
        mockMvc.perform(get("/api/payments/{paymentId}", 1L))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("LOGIN_REQUIRED"));
    }

    // key가 null이면 멱등키 헤더를 뺀다
    private MockHttpServletRequestBuilder payRequest(Held held, String key) {
        MockHttpServletRequestBuilder request = post("/api/holds/{holdId}/payments", held.holdId())
                .header(USER_ID_HEADER, held.userId())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"method\":\"CARD\"}");
        return key == null ? request : request.header(KEY, key);
    }

    // 1구역 1열 4석 회차에서 앞 2석을 잡은 상태
    private Held newHold() {
        long pid = fixtures.newPerformance(1, 1, 4);
        return fixtures.held(pid, db.seatIds(pid).subList(0, 2));
    }

    private static String key() {
        return UUID.randomUUID().toString();
    }
}
