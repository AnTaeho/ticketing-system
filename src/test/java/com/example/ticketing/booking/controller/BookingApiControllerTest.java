package com.example.ticketing.booking.controller;

import static com.example.ticketing.support.TestMvc.USER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.ticketing.notification.service.TicketIssuedHandler;
import com.example.ticketing.outbox.dto.TicketIssuedPayload;
import com.example.ticketing.support.Fixtures;
import com.example.ticketing.support.Fixtures.Confirmed;
import com.example.ticketing.support.IntegrationTest;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@IntegrationTest
@Import(StubPgTestConfig.class)
class BookingApiControllerTest {

    @Autowired
    private TicketIssuedHandler ticketIssuedHandler;
    @Autowired
    private StubPgClient stubPg;
    @Autowired
    private Fixtures fixtures;
    @Autowired
    private TestDb db;
    @Autowired
    private TestMvc testMvc;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private JsonMapper jsonMapper;

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
    @DisplayName("예매 목록은 내 예매만 최신순으로 좌석과 알림 문구를 담아 돌려준다")
    void listIsNewestFirst() throws Exception {
        long userId = fixtures.newUser();
        Confirmed older = confirmed(userId, 2);
        Confirmed newer = confirmed(userId, 1);
        fixtures.confirmed(1);
        issueTicket(older.bookingId());

        JsonNode list = testMvc.body(mockMvc.perform(get("/api/bookings").header(USER, userId))
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
        assertThat(first.get("refund").isNull()).isTrue();
        assertThat(first.get("cancellable").asBoolean()).isTrue();
        assertThat(first.get("notification").asString())
                .startsWith("「" + first.get("showTitle").asString() + "」 ")
                .contains("A구역 1열 1번 외 1석 예매가 확정됐어요")
                .endsWith(first.get("bookingNumber").asString());
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

        // 알림이 아직 없는 예매는 notification이 null
        assertThat(list.get(0).get("notification").isNull()).isTrue();
        assertThat(list.get(0).get("seats")).hasSize(1);
    }

    @Test
    @DisplayName("상세 → 취소 → 상세 흐름에서 예매가 CANCELLED, 환불이 REQUESTED로 바뀐다")
    void detailAndCancel() throws Exception {
        Confirmed b = fixtures.confirmed(2);
        long userId = b.userId();
        issueTicket(b.bookingId());

        mockMvc.perform(get("/api/bookings/{id}", b.bookingId()).header(USER, userId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bookingId").value(b.bookingId()))
                .andExpect(jsonPath("$.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.cancellable").value(true));

        JsonNode cancel = testMvc.body(mockMvc.perform(post("/api/bookings/{id}/cancel", b.bookingId())
                        .header(USER, userId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bookingId").value(b.bookingId()))
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.refund").value("REQUESTED"))
                .andReturn());
        assertThat(cancel.propertyNames()).containsExactlyInAnyOrder("bookingId", "status", "refund");

        mockMvc.perform(get("/api/bookings/{id}", b.bookingId()).header(USER, userId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.cancelledAt").isString())
                .andExpect(jsonPath("$.refund").value("REQUESTED"))
                .andExpect(jsonPath("$.cancellable").value(false))
                .andExpect(jsonPath("$.seats.length()").value(2))
                .andExpect(jsonPath("$.notification").isString());

        // 두 번째 취소도 같은 200
        mockMvc.perform(post("/api/bookings/{id}/cancel", b.bookingId()).header(USER, userId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.refund").value("REQUESTED"));
        assertThat(db.outboxCount("REFUND_REQUESTED", b.paymentId())).isEqualTo(1);
    }

    @Test
    @DisplayName("남의 예매, 시작한 회차의 취소, 로그인 없는 요청은 오류 코드로 거절한다")
    void errors() throws Exception {
        Confirmed b = fixtures.confirmed(1);
        long userId = b.userId();
        long stranger = fixtures.newUser();

        mockMvc.perform(get("/api/bookings/{id}", b.bookingId()).header(USER, stranger))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("BOOKING_NOT_FOUND"));
        mockMvc.perform(post("/api/bookings/{id}/cancel", b.bookingId()).header(USER, stranger))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("BOOKING_NOT_FOUND"));
        mockMvc.perform(get("/api/bookings"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("LOGIN_REQUIRED"));

        jdbcTemplate.update("UPDATE performance SET starts_at = NOW(6) - INTERVAL 1 SECOND WHERE id = ?", b.pid());
        mockMvc.perform(post("/api/bookings/{id}/cancel", b.bookingId()).header(USER, userId))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CANCEL_NOT_ALLOWED"));
        mockMvc.perform(get("/api/bookings/{id}", b.bookingId()).header(USER, userId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.cancellable").value(false));
    }

    // 같은 사용자가 예매를 여러 건 가지도록 사용자를 정해 결제까지 마친다. 좌석은 A구역 1열 1번부터 seatCount석
    private Confirmed confirmed(long userId, int seatCount) {
        long pid = fixtures.newPerformance(1, 1, 6);
        return fixtures.confirmed(pid, userId, db.seatIds(pid).subList(0, seatCount));
    }

    // 워커 대신 발권 핸들러를 바로 불러 알림을 만든다
    private void issueTicket(long bookingId) {
        String payload = jdbcTemplate.queryForObject("SELECT payload FROM outbox_task "
                + "WHERE task_type = 'TICKET_ISSUED' AND aggregate_id = ?", String.class, bookingId);
        ticketIssuedHandler.handle(jsonMapper.readValue(payload, TicketIssuedPayload.class));
    }
}
