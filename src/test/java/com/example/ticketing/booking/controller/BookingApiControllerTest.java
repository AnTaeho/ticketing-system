package com.example.ticketing.booking.controller;

import static com.example.ticketing.common.web.HttpNames.USER_ID_HEADER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.ticketing.support.Fixtures;
import com.example.ticketing.support.Fixtures.Confirmed;
import com.example.ticketing.support.IntegrationTest;
import com.example.ticketing.payment.client.PgResult;
import com.example.ticketing.support.StubPgClient;
import com.example.ticketing.support.StubPgTestConfig;
import com.example.ticketing.support.TestDb;
import com.example.ticketing.support.TestMvc;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;

@IntegrationTest
@Import(StubPgTestConfig.class)
class BookingApiControllerTest {

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
    @DisplayName("예매 목록은 내 예매만 최신순으로 좌석을 담아 돌려준다")
    void listIsNewestFirst() throws Exception {
        long userId = fixtures.newUser();
        Confirmed older = confirmed(userId, 2);
        Confirmed newer = confirmed(userId, 1);
        fixtures.confirmed(1);

        JsonNode list = testMvc.body(mockMvc.perform(get("/api/bookings").header(USER_ID_HEADER, userId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].bookingId").value(newer.bookingId()))
                .andExpect(jsonPath("$[1].bookingId").value(older.bookingId()))
                .andReturn());

        JsonNode first = list.get(1);
        assertThat(first.get("bookingNumber").asString()).matches("B\\d{8}-\\d{6,}");
        assertThat(first.get("status").asString()).isEqualTo("CONFIRMED");
        assertThat(first.get("totalAmount").asInt()).isEqualTo(older.totalAmount());
        assertThat(first.get("showTitle").asString()).startsWith("테스트 공연 ");
        assertThat(first.get("venueName").asString()).isEqualTo("테스트 공연장");
        assertThat(first.get("startsAt").isString()).isTrue();
        assertThat(first.get("createdAt").isString()).isTrue();
        assertThat(first.get("cancelledAt").isNull()).isTrue();
        assertThat(first.get("cancellable").asBoolean()).isTrue();
        JsonNode seats = first.get("seats");
        assertThat(seats).hasSize(2);
        assertThat(seats.get(0).get("seatId").asLong()).isEqualTo(older.seatIds().get(0));
        assertThat(seats.get(0).get("section").asString()).isEqualTo("A");
        assertThat(seats.get(0).get("rowNo").asInt()).isEqualTo(1);
        assertThat(seats.get(0).get("seatNo").asInt()).isEqualTo(1);
        assertThat(seats.get(1).get("seatNo").asInt()).isEqualTo(2);
        assertThat(seats.get(0).get("grade").isString()).isTrue();
        assertThat(seats.get(0).get("price").asInt() + seats.get(1).get("price").asInt())
                .isEqualTo(older.totalAmount());

        assertThat(list.get(0).get("seats")).hasSize(1);
    }

    @Test
    @DisplayName("상세 → 취소 → 상세 흐름에서 예매가 CANCELLED로 바뀐다")
    void detailAndCancel() throws Exception {
        Confirmed b = fixtures.confirmed(2);
        long userId = b.userId();

        mockMvc.perform(get("/api/bookings/{id}", b.bookingId()).header(USER_ID_HEADER, userId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bookingId").value(b.bookingId()))
                .andExpect(jsonPath("$.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.cancellable").value(true));

        JsonNode cancel = testMvc.body(mockMvc.perform(post("/api/bookings/{id}/cancel", b.bookingId())
                        .header(USER_ID_HEADER, userId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bookingId").value(b.bookingId()))
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andReturn());
        assertThat(cancel.propertyNames()).containsExactlyInAnyOrder("bookingId", "status");

        mockMvc.perform(get("/api/bookings/{id}", b.bookingId()).header(USER_ID_HEADER, userId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.cancelledAt").isString())
                .andExpect(jsonPath("$.cancellable").value(false))
                .andExpect(jsonPath("$.seats.length()").value(2));
    }

    @Test
    @DisplayName("PG 환불이 결과 모름이면 취소는 503 REFUND_FAILED이고 예매는 확정 그대로다")
    void cancelRefundFailed() throws Exception {
        Confirmed b = fixtures.confirmed(1);
        stubPg.cancelWith(PgResult.unknown("read timeout 3s"));

        mockMvc.perform(post("/api/bookings/{id}/cancel", b.bookingId()).header(USER_ID_HEADER, b.userId()))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("REFUND_FAILED"));

        mockMvc.perform(get("/api/bookings/{id}", b.bookingId()).header(USER_ID_HEADER, b.userId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.cancellable").value(true));
    }

    @Test
    @DisplayName("남의 예매를 조회하면 404 BOOKING_NOT_FOUND로 답한다")
    void othersBookingIsNotFound() throws Exception {
        Confirmed b = fixtures.confirmed(1);

        mockMvc.perform(get("/api/bookings/{id}", b.bookingId()).header(USER_ID_HEADER, fixtures.newUser()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("BOOKING_NOT_FOUND"));
    }

    @Test
    @DisplayName("사용자 식별이 없으면 목록·상세·취소 모두 401 LOGIN_REQUIRED로 답한다")
    void loginRequired() throws Exception {
        for (MockHttpServletRequestBuilder request : new MockHttpServletRequestBuilder[]{
                get("/api/bookings"), get("/api/bookings/{id}", 1L), post("/api/bookings/{id}/cancel", 1L)}) {
            mockMvc.perform(request)
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("LOGIN_REQUIRED"));
        }
    }

    // 같은 사용자가 예매를 여러 건 가지도록 사용자를 정해 결제까지 마친다. 좌석은 A구역 1열 1번부터 seatCount석
    private Confirmed confirmed(long userId, int seatCount) {
        long pid = fixtures.newPerformance(1, 1, 6);
        return fixtures.confirmed(pid, userId, db.seatIds(pid).subList(0, seatCount));
    }
}
