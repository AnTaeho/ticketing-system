package com.example.ticketing.booking.service;

import static java.util.stream.Collectors.groupingBy;
import static java.util.stream.Collectors.mapping;
import static java.util.stream.Collectors.toList;

import com.example.ticketing.booking.domain.Booking;
import com.example.ticketing.booking.dto.BookingResult;
import com.example.ticketing.booking.dto.BookingSeatResult;
import com.example.ticketing.booking.dto.CancelTarget;
import com.example.ticketing.booking.repository.BookingRepository;
import com.example.ticketing.booking.repository.BookingSeatRepository;
import com.example.ticketing.common.error.ApiException;
import com.example.ticketing.common.error.ErrorCode;
import com.example.ticketing.common.time.DbClock;
import com.example.ticketing.show.dto.PerformanceSummary;
import com.example.ticketing.show.service.PerformanceReader;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

// 예매 목록은 예매·회차·공연·좌석 사본을 한 번씩 읽어 묶는다. 예매마다 읽지 않는다
@Component
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class BookingReader {

    private final BookingRepository bookingRepository;
    private final BookingSeatRepository bookingSeatRepository;
    private final PerformanceReader performanceReader;
    private final DbClock dbClock;

    public Booking getBooking(long userId, long bookingId) {
        return bookingRepository.findByIdAndUserId(bookingId, userId)
                .orElseThrow(() -> new ApiException(ErrorCode.BOOKING_NOT_FOUND, Map.of("bookingId", bookingId)));
    }

    // 최신순
    public List<BookingResult> findByUser(long userId) {
        return resultsOf(bookingRepository.findByUserIdOrderByCreatedAtDescIdDesc(userId));
    }

    public BookingResult find(long userId, long bookingId) {
        return resultsOf(List.of(getBooking(userId, bookingId))).get(0);
    }

    public CancelTarget findCancelTarget(long userId, long bookingId) {
        return bookingRepository.findCancelTarget(userId, bookingId)
                .orElseThrow(() -> new ApiException(ErrorCode.BOOKING_NOT_FOUND, Map.of("bookingId", bookingId)));
    }

    private List<BookingResult> resultsOf(List<Booking> bookings) {
        if (bookings.isEmpty()) {
            return List.of();
        }
        Map<Long, PerformanceSummary> performances = performanceReader.findAll(
                bookings.stream().map(Booking::getPerformanceId).distinct().toList());
        Map<Long, List<BookingSeatResult>> seats = bookingSeatRepository
                .findByBookingIds(bookings.stream().map(Booking::getId).toList()).stream()
                .collect(groupingBy(seat -> seat.getId().getBookingId(), mapping(BookingSeatResult::from, toList())));
        LocalDateTime dbNow = dbClock.now();
        return bookings.stream()
                .map(booking -> BookingResult.from(booking, performanceOf(performances, booking),
                        seats.getOrDefault(booking.getId(), List.of()), dbNow))
                .toList();
    }

    private static PerformanceSummary performanceOf(Map<Long, PerformanceSummary> performances, Booking booking) {
        PerformanceSummary performance = performances.get(booking.getPerformanceId());
        if (performance == null) {
            throw new IllegalStateException("예매 " + booking.getId() + "의 회차가 없다");
        }
        return performance;
    }
}
