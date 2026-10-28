package com.example.ticketing.booking.service;

import com.example.ticketing.booking.domain.BookingRow;
import com.example.ticketing.booking.domain.BookingSeatRow;
import com.example.ticketing.booking.dto.BookingResult;
import com.example.ticketing.booking.dto.BookingSeatResult;
import com.example.ticketing.booking.repository.BookingJdbcRepository;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

// 예매 목록은 예매 한 번 + 좌석 한 번, 두 쿼리로 읽는다
@Component
@RequiredArgsConstructor
public class BookingReader {

    private final BookingJdbcRepository bookingJdbcRepository;

    // 최신순
    public List<BookingResult> findByUser(long userId) {
        return withSeats(bookingJdbcRepository.findByUser(userId));
    }

    public Optional<BookingResult> findOne(long userId, long bookingId) {
        return withSeats(bookingJdbcRepository.findOne(userId, bookingId).stream().toList()).stream().findFirst();
    }

    private List<BookingResult> withSeats(List<BookingRow> rows) {
        if (rows.isEmpty()) {
            return List.of();
        }
        Map<Long, List<BookingSeatResult>> seats = new LinkedHashMap<>();
        for (BookingSeatRow seat : bookingJdbcRepository.findSeats(rows.stream().map(BookingRow::id).toList())) {
            seats.computeIfAbsent(seat.bookingId(), id -> new ArrayList<>()).add(BookingSeatResult.from(seat));
        }
        return rows.stream().map(row -> BookingResult.from(row, seats.getOrDefault(row.id(), List.of()))).toList();
    }
}
