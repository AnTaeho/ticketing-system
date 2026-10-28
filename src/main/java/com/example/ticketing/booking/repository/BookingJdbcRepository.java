package com.example.ticketing.booking.repository;

import com.example.ticketing.booking.domain.BookingPayment;
import com.example.ticketing.booking.domain.BookingRow;
import com.example.ticketing.booking.domain.BookingSeatRow;
import com.example.ticketing.booking.domain.BookingStatus;
import com.example.ticketing.seat.domain.SeatGrade;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

// 결제 상태는 booking_payment를 직접 읽는다. 예매 기능이 결제 기능의 코드를 부르지 않게 하려고다
@Component
@RequiredArgsConstructor
public class BookingJdbcRepository {

    private static final String BOOKING_SQL = """
            SELECT b.id, b.booking_number, b.status, b.created_at, b.cancelled_at, b.total_amount,
                   s.title, s.venue_name, p.starts_at, p.starts_at > NOW(6) AS not_started,
                   bp.status = 'CANCELLED' AS payment_refunded, n.message AS notification
              FROM booking b
              JOIN performance p ON p.id = b.performance_id
              JOIN show_info s ON s.id = p.show_id
              JOIN booking_payment bp ON bp.id = b.payment_id
              LEFT JOIN notification n ON n.booking_id = b.id
             WHERE b.user_id = :userId""";

    private static final String SEATS_SQL = """
            SELECT booking_id, seat_id, section, row_no, seat_no, grade, price
              FROM booking_seat
             WHERE booking_id IN (:bookingIds)
             ORDER BY booking_id, section, row_no, seat_no""";

    private static final String PAYMENT_SQL = """
            SELECT bp.id, bp.order_id, bp.status = 'CANCELLED' AS refunded
              FROM booking_payment bp
             WHERE bp.id = :paymentId""";

    private final NamedParameterJdbcTemplate jdbcTemplate;

    // 최신순
    public List<BookingRow> findByUser(long userId) {
        return jdbcTemplate.query(BOOKING_SQL + " ORDER BY b.created_at DESC, b.id DESC",
                Map.of("userId", userId), BookingJdbcRepository::bookingRow);
    }

    public Optional<BookingRow> findOne(long userId, long bookingId) {
        return jdbcTemplate.query(BOOKING_SQL + " AND b.id = :bookingId",
                Map.of("userId", userId, "bookingId", bookingId), BookingJdbcRepository::bookingRow).stream().findFirst();
    }

    public List<BookingSeatRow> findSeats(Collection<Long> bookingIds) {
        return jdbcTemplate.query(SEATS_SQL, Map.of("bookingIds", bookingIds),
                (rs, i) -> new BookingSeatRow(rs.getLong("booking_id"), rs.getLong("seat_id"),
                        rs.getString("section"), rs.getInt("row_no"), rs.getInt("seat_no"),
                        SeatGrade.valueOf(rs.getString("grade")), rs.getInt("price")));
    }

    public Optional<BookingPayment> findPayment(long paymentId) {
        return jdbcTemplate.query(PAYMENT_SQL, Map.of("paymentId", paymentId),
                (rs, i) -> new BookingPayment(rs.getLong("id"), rs.getString("order_id"), rs.getBoolean("refunded")))
                .stream().findFirst();
    }

    private static BookingRow bookingRow(ResultSet rs, int i) throws SQLException {
        return new BookingRow(rs.getLong("id"), rs.getString("booking_number"),
                BookingStatus.valueOf(rs.getString("status")), rs.getObject("created_at", LocalDateTime.class),
                rs.getObject("cancelled_at", LocalDateTime.class), rs.getInt("total_amount"), rs.getString("title"),
                rs.getString("venue_name"), rs.getObject("starts_at", LocalDateTime.class),
                rs.getBoolean("not_started"), rs.getBoolean("payment_refunded"), rs.getString("notification"));
    }
}
