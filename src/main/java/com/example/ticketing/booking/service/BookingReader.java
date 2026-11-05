package com.example.ticketing.booking.service;

import static java.util.stream.Collectors.groupingBy;
import static java.util.stream.Collectors.mapping;
import static java.util.stream.Collectors.toList;

import com.example.ticketing.booking.dto.BookingResult;
import com.example.ticketing.booking.dto.BookingSeatResult;
import com.example.ticketing.booking.dto.CancelTarget;
import com.example.ticketing.booking.repository.BookingRepository;
import com.example.ticketing.booking.repository.BookingSeatRepository;
import com.example.ticketing.common.error.ApiException;
import com.example.ticketing.common.error.ErrorCode;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

// 예매 목록은 예매 한 번 + 좌석 한 번, 두 쿼리를 한 읽기 전용 트랜잭션에서 읽는다
@Component
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class BookingReader {

    private final BookingRepository bookingRepository;
    private final BookingSeatRepository bookingSeatRepository;

    // 최신순
    public List<BookingResult> findByUser(long userId) {
        return withSeats(bookingRepository.findResults(userId));
    }

    public BookingResult find(long userId, long bookingId) {
        BookingResult result = bookingRepository.findResult(userId, bookingId)
                .orElseThrow(() -> new ApiException(ErrorCode.BOOKING_NOT_FOUND, Map.of("bookingId", bookingId)));
        return withSeats(List.of(result)).get(0);
    }

    public CancelTarget findCancelTarget(long userId, long bookingId) {
        return bookingRepository.findCancelTarget(userId, bookingId)
                .orElseThrow(() -> new ApiException(ErrorCode.BOOKING_NOT_FOUND, Map.of("bookingId", bookingId)));
    }

    private List<BookingResult> withSeats(List<BookingResult> results) {
        if (results.isEmpty()) {
            return List.of();
        }
        Map<Long, List<BookingSeatResult>> seats = bookingSeatRepository
                .findByBookingIds(results.stream().map(BookingResult::bookingId).toList()).stream()
                .collect(groupingBy(seat -> seat.getId().getBookingId(), mapping(BookingSeatResult::from, toList())));
        return results.stream().map(result -> result.withSeats(seats.getOrDefault(result.bookingId(), List.of())))
                .toList();
    }
}
