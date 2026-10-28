package com.example.ticketing.booking.controller;

import com.example.ticketing.booking.dto.BookingResponse;
import com.example.ticketing.booking.dto.CancelResponse;
import com.example.ticketing.booking.service.BookingService;
import com.example.ticketing.common.web.CurrentUser;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class BookingApiController {

    private final BookingService bookingService;

    @GetMapping("/api/bookings")
    public List<BookingResponse> list(@CurrentUser Long userId) {
        return bookingService.list(userId).stream().map(BookingResponse::from).toList();
    }

    @GetMapping("/api/bookings/{bookingId}")
    public BookingResponse detail(@CurrentUser Long userId, @PathVariable long bookingId) {
        return BookingResponse.from(bookingService.detail(userId, bookingId));
    }

    @PostMapping("/api/bookings/{bookingId}/cancel")
    public CancelResponse cancel(@CurrentUser Long userId, @PathVariable long bookingId) {
        return CancelResponse.from(bookingService.cancel(userId, bookingId));
    }
}
