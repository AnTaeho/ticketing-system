package com.example.ticketing.booking.service;

import com.example.ticketing.booking.domain.Booking;
import com.example.ticketing.booking.domain.BookingNumber;
import com.example.ticketing.booking.dto.IssueBookingCommand;
import com.example.ticketing.booking.dto.IssuedBooking;
import com.example.ticketing.booking.repository.BookingRepository;
import com.example.ticketing.booking.repository.BookingSeatRepository;
import com.example.ticketing.common.jdbc.RowCounts;
import com.example.ticketing.outbox.domain.OutboxTaskType;
import com.example.ticketing.outbox.service.OutboxWriter;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

// 결제 기능이 부르는 예매 발급 창구. 예매 기능은 결제 기능을 부르지 않는다
@Component
@RequiredArgsConstructor
public class BookingIssuer {

    private final BookingRepository bookingRepository;
    private final BookingSeatRepository bookingSeatRepository;
    private final OutboxWriter outboxWriter;
    private final JsonMapper jsonMapper;

    // 좌석 SOLD 전이와 같은 트랜잭션이어야 좌석 사본과 발권 작업이 확정과 함께 남는다
    @Transactional(propagation = Propagation.MANDATORY)
    public IssuedBooking issue(IssueBookingCommand command) {
        Booking booking = bookingRepository.save(Booking.confirmed(BookingNumber.of(command.dbNow(), command.holdId()),
                command.holdId(), command.paymentId(), command.userId(), command.performanceId(),
                command.totalAmount()));
        RowCounts.requireExactly(bookingSeatRepository.copySoldSeats(booking.getId(), command.holdId()),
                command.seatCount(), "booking_seat");
        outboxWriter.write(OutboxTaskType.TICKET_ISSUED, booking.getId(),
                jsonMapper.writeValueAsString(Map.of("bookingId", booking.getId())));
        return new IssuedBooking(booking.getId(), booking.getBookingNumber());
    }

    public Optional<IssuedBooking> findByHold(long holdId) {
        return bookingRepository.findByHoldId(holdId)
                .map(booking -> new IssuedBooking(booking.getId(), booking.getBookingNumber()));
    }
}
