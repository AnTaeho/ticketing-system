package com.example.ticketing.booking.purchase;

import com.example.ticketing.booking.payment.domain.BookingPaymentStatus;
import com.example.ticketing.booking.purchase.domain.BookingStatus;
import com.example.ticketing.booking.purchase.dto.BookingSeatView;
import com.example.ticketing.booking.purchase.dto.BookingView;
import com.example.ticketing.booking.purchase.dto.RefundStatus;
import com.example.ticketing.booking.show.domain.SeatGrade;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

// 예매 목록은 예매 한 번 + 좌석 한 번, 두 쿼리로 읽는다. 시작 여부는 DB 시각으로 함께 계산한다
@Component
@RequiredArgsConstructor
public class BookingViewReader {

    private static final String BOOKING_SQL = """
            SELECT b.id, b.booking_number, b.status, b.created_at, b.cancelled_at, b.total_amount,
                   s.title, s.venue_name, p.starts_at, p.starts_at > NOW(6) AS not_started,
                   bp.status AS payment_status, n.message AS notification
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

    private final NamedParameterJdbcTemplate jdbcTemplate;

    // 최신순
    public List<BookingView> findByUser(long userId) {
        List<BookingRow> rows = jdbcTemplate.query(BOOKING_SQL + " ORDER BY b.created_at DESC, b.id DESC",
                Map.of("userId", userId), BookingViewReader::bookingRow);
        return withSeats(rows);
    }

    public Optional<BookingView> findOne(long userId, long bookingId) {
        List<BookingRow> rows = jdbcTemplate.query(BOOKING_SQL + " AND b.id = :bookingId",
                Map.of("userId", userId, "bookingId", bookingId), BookingViewReader::bookingRow);
        return withSeats(rows).stream().findFirst();
    }

    private List<BookingView> withSeats(List<BookingRow> rows) {
        if (rows.isEmpty()) {
            return List.of();
        }
        Map<Long, List<BookingSeatView>> seats = new LinkedHashMap<>();
        jdbcTemplate.query(SEATS_SQL, Map.of("bookingIds", rows.stream().map(BookingRow::id).toList()), rs -> {
            seats.computeIfAbsent(rs.getLong("booking_id"), id -> new ArrayList<>())
                    .add(new BookingSeatView(rs.getLong("seat_id"), rs.getString("section"), rs.getInt("row_no"),
                            rs.getInt("seat_no"), SeatGrade.valueOf(rs.getString("grade")), rs.getInt("price")));
        });
        return rows.stream().map(row -> row.toView(seats.getOrDefault(row.id(), List.of()))).toList();
    }

    private static BookingRow bookingRow(ResultSet rs, int i) throws SQLException {
        return new BookingRow(rs.getLong("id"), rs.getString("booking_number"),
                BookingStatus.valueOf(rs.getString("status")), rs.getObject("created_at", LocalDateTime.class),
                rs.getObject("cancelled_at", LocalDateTime.class), rs.getInt("total_amount"), rs.getString("title"),
                rs.getString("venue_name"), rs.getObject("starts_at", LocalDateTime.class),
                rs.getBoolean("not_started"), BookingPaymentStatus.valueOf(rs.getString("payment_status")),
                rs.getString("notification"));
    }

    private record BookingRow(long id, String bookingNumber, BookingStatus status, LocalDateTime createdAt,
                              LocalDateTime cancelledAt, int totalAmount, String title, String venueName,
                              LocalDateTime startsAt, boolean notStarted, BookingPaymentStatus paymentStatus,
                              String notification) {

        BookingView toView(List<BookingSeatView> seats) {
            return new BookingView(id, bookingNumber, status, createdAt, cancelledAt, totalAmount, title, venueName,
                    startsAt, List.copyOf(seats), notification, RefundStatus.of(status, paymentStatus),
                    status == BookingStatus.CONFIRMED && notStarted);
        }
    }
}
