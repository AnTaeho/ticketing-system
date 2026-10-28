package com.example.ticketing.booking.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.ticketing.hold.dto.HoldCreated;
import com.example.ticketing.hold.service.SeatHoldService;
import com.example.ticketing.outbox.dto.ClaimedOutboxTask;
import com.example.ticketing.outbox.service.TicketIssuedHandler;
import com.example.ticketing.payment.dto.PaymentResponse;
import com.example.ticketing.payment.service.PaymentService;
import com.example.ticketing.support.Fixtures;
import com.example.ticketing.support.IntegrationTest;
import com.example.ticketing.support.PaymentDb;
import com.example.ticketing.support.RedisKeyCleaner;
import com.example.ticketing.support.StubPgClient;
import com.example.ticketing.support.StubPgTestConfig;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@IntegrationTest
@Import(StubPgTestConfig.class)
class BookingApiControllerTest {

    private static final String USER = "X-User-Id";

    @Autowired
    private WebApplicationContext context;
    @Autowired
    private SeatHoldService seatHoldService;
    @Autowired
    private PaymentService paymentService;
    @Autowired
    private TicketIssuedHandler ticketIssuedHandler;
    @Autowired
    private StubPgClient stubPg;
    @Autowired
    private Fixtures fixtures;
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
    @DisplayName("GET /api/bookings: 내 예매만 최신순, 공연·회차·좌석(booking_seat 사본)·알림 문구·cancellable, 환불은 null")
    void listIsNewestFirst() throws Exception {
        long userId = fixtures.newUser();
        Booked older = book(userId, 2);
        Booked newer = book(userId, 1);
        book(fixtures.newUser(), 1);
        issueTicket(older.bookingId);

        JsonNode list = body(mockMvc.perform(get("/api/bookings").header(USER, userId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].bookingId").value(newer.bookingId))
                .andExpect(jsonPath("$[1].bookingId").value(older.bookingId))
                .andReturn());

        JsonNode first = list.get(1);
        assertThat(first.get("bookingNumber").asString()).matches("B\\d{8}-\\d{6,}");
        assertThat(first.get("status").asString()).isEqualTo("CONFIRMED");
        assertThat(first.get("totalAmount").asInt()).isEqualTo(older.totalAmount);
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
        assertThat(seats.get(0).get("seatId").asLong()).isEqualTo(older.seatIds.get(0));
        assertThat(seats.get(0).get("section").asString()).isEqualTo("A");
        assertThat(seats.get(0).get("rowNo").asInt()).isEqualTo(1);
        assertThat(seats.get(0).get("seatNo").asInt()).isEqualTo(1);
        assertThat(seats.get(1).get("seatNo").asInt()).isEqualTo(2);
        assertThat(seats.get(0).get("grade").isString()).isTrue();
        assertThat(seats.get(0).get("price").asInt() + seats.get(1).get("price").asInt()).isEqualTo(older.totalAmount);

        // 알림이 아직 없는 예매는 notification이 null
        assertThat(list.get(0).get("notification").isNull()).isTrue();
        assertThat(list.get(0).get("seats")).hasSize(1);
    }

    @Test
    @DisplayName("GET 상세 → POST 취소 200 {bookingId, status: CANCELLED, refund: REQUESTED} → GET 상세는 CANCELLED·cancelledAt·"
            + "환불 REQUESTED·cancellable false, 좌석·알림은 그대로")
    void detailAndCancel() throws Exception {
        long userId = fixtures.newUser();
        Booked b = book(userId, 2);
        issueTicket(b.bookingId);

        MvcResult before = mockMvc.perform(get("/api/bookings/{id}", b.bookingId).header(USER, userId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bookingId").value(b.bookingId))
                .andExpect(jsonPath("$.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.cancellable").value(true))
                .andReturn();
        System.out.println("[S5.3] GET /api/bookings/{id} 응답(취소 전): " + before.getResponse().getContentAsString());

        JsonNode cancel = body(mockMvc.perform(post("/api/bookings/{id}/cancel", b.bookingId).header(USER, userId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bookingId").value(b.bookingId))
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.refund").value("REQUESTED"))
                .andReturn());
        System.out.println("[S5.3] POST /api/bookings/{id}/cancel 응답: " + cancel);
        assertThat(cancel.propertyNames()).containsExactlyInAnyOrder("bookingId", "status", "refund");

        MvcResult after = mockMvc.perform(get("/api/bookings/{id}", b.bookingId).header(USER, userId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.cancelledAt").isString())
                .andExpect(jsonPath("$.refund").value("REQUESTED"))
                .andExpect(jsonPath("$.cancellable").value(false))
                .andExpect(jsonPath("$.seats.length()").value(2))
                .andExpect(jsonPath("$.notification").isString())
                .andReturn();
        System.out.println("[S5.3] GET /api/bookings/{id} 응답(취소 후): " + after.getResponse().getContentAsString());

        // 두 번째 취소도 같은 200
        mockMvc.perform(post("/api/bookings/{id}/cancel", b.bookingId).header(USER, userId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.refund").value("REQUESTED"));
        assertThat(db.outboxCount("REFUND_REQUESTED", b.paymentId)).isEqualTo(1);
    }

    @Test
    @DisplayName("남의 예매 상세·취소는 404 BOOKING_NOT_FOUND, 시작한 회차 취소는 409 CANCEL_NOT_ALLOWED, 로그인 없으면 401")
    void errors() throws Exception {
        long userId = fixtures.newUser();
        Booked b = book(userId, 1);
        long stranger = fixtures.newUser();

        mockMvc.perform(get("/api/bookings/{id}", b.bookingId).header(USER, stranger))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("BOOKING_NOT_FOUND"));
        mockMvc.perform(post("/api/bookings/{id}/cancel", b.bookingId).header(USER, stranger))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("BOOKING_NOT_FOUND"));
        mockMvc.perform(get("/api/bookings"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("LOGIN_REQUIRED"));

        jdbcTemplate.update("UPDATE performance SET starts_at = NOW(6) - INTERVAL 1 SECOND WHERE id = ?", b.pid);
        mockMvc.perform(post("/api/bookings/{id}/cancel", b.bookingId).header(USER, userId))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CANCEL_NOT_ALLOWED"));
        mockMvc.perform(get("/api/bookings/{id}", b.bookingId).header(USER, userId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.cancellable").value(false));
    }

    private Booked book(long userId, int seatCount) {
        long pid = fixtures.newPerformance(1, 1, 6);
        performances.add(pid);
        List<Long> seatIds = db.seatIds(pid).subList(0, seatCount);
        HoldCreated hold = seatHoldService.hold(userId, pid, seatIds, fixtures.admit(pid, userId));
        PaymentResponse paid = paymentService.pay(userId, hold.holdId(), UUID.randomUUID().toString(),
                Optional.empty());
        assertThat(paid.status()).isEqualTo(PaymentResponse.Status.CONFIRMED);
        return new Booked(pid, paid.paymentId(), paid.bookingId(), List.copyOf(seatIds), hold.totalAmount());
    }

    // 워커 대신 발권 핸들러를 바로 불러 알림을 만든다
    private void issueTicket(long bookingId) {
        Map<String, Object> task = jdbcTemplate.queryForMap("SELECT id, payload FROM outbox_task "
                + "WHERE task_type = 'TICKET_ISSUED' AND aggregate_id = ?", bookingId);
        ticketIssuedHandler.handle(new ClaimedOutboxTask(((Number) task.get("id")).longValue(), "TICKET_ISSUED",
                bookingId, (String) task.get("payload"), 0, null));
    }

    private JsonNode body(MvcResult result) throws Exception {
        return jsonMapper.readTree(result.getResponse().getContentAsString());
    }

    private record Booked(long pid, long paymentId, long bookingId, List<Long> seatIds, int totalAmount) {
    }
}
