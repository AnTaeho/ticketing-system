package com.example.ticketing.booking.outbox.handler;

import com.example.ticketing.booking.notification.Notification;
import com.example.ticketing.booking.notification.NotificationRepository;
import com.example.ticketing.booking.outbox.ClaimedOutboxTask;
import com.example.ticketing.booking.outbox.domain.OutboxTaskType;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import lombok.extern.slf4j.Slf4j;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

// 발권 알림을 남긴다. 이미 있으면(재처리) uk_notification_booking 위반을 성공으로 본다
@Slf4j
@Component
public class TicketIssuedHandler implements OutboxHandler {

    private static final DateTimeFormatter STARTS_AT = DateTimeFormatter.ofPattern("yyyy.M.d(E) HH:mm", Locale.KOREAN);
    private static final int MAX_TITLE = 100;

    private static final String BOOKING_SQL = """
            SELECT b.user_id, b.booking_number, s.title, p.starts_at
              FROM booking b
              JOIN performance p ON p.id = b.performance_id
              JOIN show_info s ON s.id = p.show_id
             WHERE b.id = ?""";

    private static final String SEATS_SQL = """
            SELECT section, row_no, seat_no FROM booking_seat
             WHERE booking_id = ? ORDER BY section, row_no, seat_no""";

    private final NotificationRepository notificationRepository;
    private final JdbcTemplate jdbcTemplate;
    private final JsonMapper jsonMapper;
    private final TransactionTemplate write;

    public TicketIssuedHandler(NotificationRepository notificationRepository, JdbcTemplate jdbcTemplate,
                               JsonMapper jsonMapper, PlatformTransactionManager transactionManager) {
        this.notificationRepository = notificationRepository;
        this.jdbcTemplate = jdbcTemplate;
        this.jsonMapper = jsonMapper;
        this.write = new TransactionTemplate(transactionManager);
        this.write.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    @Override
    public OutboxTaskType type() {
        return OutboxTaskType.TICKET_ISSUED;
    }

    @Override
    public void handle(ClaimedOutboxTask task) {
        long bookingId = jsonMapper.readTree(task.payload()).required("bookingId").asLong();
        try {
            write.executeWithoutResult(status -> issue(bookingId));
        } catch (DataIntegrityViolationException e) {
            if (!violates(e, Notification.UK_BOOKING)) {
                throw e;
            }
            log.info("[booking outbox] 예매 {} 발권 알림은 이미 있다", bookingId);
        }
    }

    private void issue(long bookingId) {
        if (notificationRepository.existsByBookingId(bookingId)) {
            return;
        }
        List<BookingRow> bookings = jdbcTemplate.query(BOOKING_SQL,
                (rs, i) -> new BookingRow(rs.getLong("user_id"), rs.getString("booking_number"),
                        rs.getString("title"), rs.getObject("starts_at", LocalDateTime.class)),
                bookingId);
        if (bookings.isEmpty()) {
            throw new IllegalStateException("예매 " + bookingId + "가 없다");
        }
        List<SeatRow> seats = jdbcTemplate.query(SEATS_SQL,
                (rs, i) -> new SeatRow(rs.getString("section"), rs.getInt("row_no"), rs.getInt("seat_no")),
                bookingId);
        if (seats.isEmpty()) {
            throw new IllegalStateException("예매 " + bookingId + "의 좌석이 없다");
        }
        BookingRow booking = bookings.get(0);
        notificationRepository.save(Notification.ticketIssued(booking.userId(), bookingId,
                message(booking.title(), booking.startsAt(), seats, booking.bookingNumber())));
    }

    static String message(String title, LocalDateTime startsAt, List<SeatRow> seats, String bookingNumber) {
        SeatRow first = seats.get(0);
        String others = seats.size() > 1 ? " 외 " + (seats.size() - 1) + "석" : "";
        return "「%s」 %s %s구역 %d열 %d번%s 예매가 확정됐어요. 예매번호 %s".formatted(
                shorten(title), startsAt.format(STARTS_AT), first.section(), first.rowNo(), first.seatNo(), others,
                bookingNumber);
    }

    private static String shorten(String title) {
        return title.length() <= MAX_TITLE ? title : title.substring(0, MAX_TITLE - 1) + "…";
    }

    private static boolean violates(DataIntegrityViolationException e, String constraint) {
        for (Throwable cause = e; cause != null && cause.getCause() != cause; cause = cause.getCause()) {
            if (cause instanceof ConstraintViolationException violation
                    && violation.getConstraintName() != null && violation.getConstraintName().contains(constraint)) {
                return true;
            }
            if (cause.getMessage() != null && cause.getMessage().contains(constraint)) {
                return true;
            }
        }
        return false;
    }

    record SeatRow(String section, int rowNo, int seatNo) {
    }

    private record BookingRow(long userId, String bookingNumber, String title, LocalDateTime startsAt) {
    }
}
