package com.example.ticketing.notification.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.ticketing.outbox.dto.ClaimedOutboxTask;
import com.example.ticketing.outbox.dto.TicketIssuedPayload;
import com.example.ticketing.outbox.service.OutboxTaskTransaction;
import com.example.ticketing.support.Concurrently;
import com.example.ticketing.support.Concurrently.Results;
import com.example.ticketing.support.Fixtures;
import com.example.ticketing.support.Fixtures.Confirmed;
import com.example.ticketing.support.IntegrationTest;
import com.example.ticketing.support.StubPgClient;
import com.example.ticketing.support.StubPgTestConfig;
import com.example.ticketing.support.TestDb;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.json.JsonMapper;

// 워커 없이 집은 발권 작업을 핸들러에 바로 넘긴다. 워커의 집기·재시도는 OutboxDispatchSchedulerTest가 본다
@IntegrationTest
@Import(StubPgTestConfig.class)
class TicketIssuedHandlerTest {

    @Autowired
    private TicketIssuedHandler ticketIssuedHandler;
    @Autowired
    private OutboxTaskTransaction outboxTaskTransaction;
    @Autowired
    private StubPgClient stubPg;
    @Autowired
    private Fixtures fixtures;
    @Autowired
    private TestDb db;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private JsonMapper jsonMapper;

    @BeforeEach
    void setUp() {
        stubPg.reset();
        db.parkOutboxTasks();
    }

    @AfterEach
    void tearDown() {
        stubPg.reset();
    }

    @Test
    @DisplayName("같은 발권 작업을 핸들러로 두 번 처리해도 알림은 1건이다")
    void ticketIssuedIsIdempotent() {
        Confirmed c = fixtures.confirmed(2);
        TicketIssuedPayload payload = payloadOf(outboxTaskTransaction.claim(50).get(0));

        ticketIssuedHandler.handle(payload);
        String first = notificationMessage(c.bookingId());
        ticketIssuedHandler.handle(payload);

        assertThat(notificationCount(c.bookingId())).isEqualTo(1);
        assertThat(notificationMessage(c.bookingId())).isEqualTo(first);
    }

    @Test
    @DisplayName("같은 발권 작업을 5스레드가 동시에 처리해도 예외 없이 알림은 1건이다")
    void concurrentSameTicketIssuedWritesOnce() {
        Confirmed c = fixtures.confirmed(2);
        TicketIssuedPayload payload = payloadOf(outboxTaskTransaction.claim(50).get(0));

        Results<Void> results = Concurrently.of(5)
                .readyWithin(Duration.ofSeconds(10))
                .within(Duration.ofSeconds(60))
                .runAll(i -> ticketIssuedHandler.handle(payload));

        assertThat(results.errors()).isEmpty();
        assertThat(notificationCount(c.bookingId())).isEqualTo(1);
    }

    private TicketIssuedPayload payloadOf(ClaimedOutboxTask task) {
        return jsonMapper.readValue(task.payload(), TicketIssuedPayload.class);
    }

    private long notificationCount(long bookingId) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM notification WHERE booking_id = ?", Long.class,
                bookingId);
    }

    private String notificationMessage(long bookingId) {
        return jdbcTemplate.queryForObject("SELECT message FROM notification WHERE booking_id = ?", String.class,
                bookingId);
    }
}
