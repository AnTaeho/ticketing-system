package com.example.ticketing.notification.repository;

import com.example.ticketing.notification.domain.IssuedBookingRow;
import com.example.ticketing.notification.domain.IssuedSeatRow;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

// 예매·회차·좌석 사본은 예매 기능의 테이블이다. 알림은 읽기만 하므로 예매 기능의 코드를 부르지 않고 직접 읽는다
@Component
@RequiredArgsConstructor
public class NotificationJdbcRepository {

    private static final String ISSUED_BOOKING_SQL = """
            SELECT b.user_id, b.booking_number, s.title, p.starts_at
              FROM booking b
              JOIN performance p ON p.id = b.performance_id
              JOIN show_info s ON s.id = p.show_id
             WHERE b.id = :bookingId""";

    private static final String ISSUED_SEATS_SQL = """
            SELECT section, row_no, seat_no FROM booking_seat
             WHERE booking_id = :bookingId ORDER BY section, row_no, seat_no""";

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public Optional<IssuedBookingRow> findIssuedBooking(long bookingId) {
        return jdbcTemplate.query(ISSUED_BOOKING_SQL, Map.of("bookingId", bookingId),
                (rs, i) -> new IssuedBookingRow(rs.getLong("user_id"), rs.getString("booking_number"),
                        rs.getString("title"), rs.getObject("starts_at", LocalDateTime.class)))
                .stream().findFirst();
    }

    public List<IssuedSeatRow> findIssuedSeats(long bookingId) {
        return jdbcTemplate.query(ISSUED_SEATS_SQL, Map.of("bookingId", bookingId),
                (rs, i) -> new IssuedSeatRow(rs.getString("section"), rs.getInt("row_no"), rs.getInt("seat_no")));
    }
}
