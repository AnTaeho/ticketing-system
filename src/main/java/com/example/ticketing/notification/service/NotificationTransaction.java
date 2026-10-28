package com.example.ticketing.notification.service;

import com.example.ticketing.notification.domain.IssuedBookingRow;
import com.example.ticketing.notification.domain.IssuedSeatRow;
import com.example.ticketing.notification.domain.Notification;
import com.example.ticketing.notification.domain.TicketIssuedMessage;
import com.example.ticketing.notification.repository.NotificationJdbcRepository;
import com.example.ticketing.notification.repository.NotificationRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
public class NotificationTransaction {

    private final NotificationRepository notificationRepository;
    private final NotificationJdbcRepository notificationJdbcRepository;

    // 이미 있으면 아무것도 하지 않는다. 동시에 처리한 쪽과 겹치면 uk_notification_booking 위반 예외로 끝난다
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void issueTicket(long bookingId) {
        if (notificationRepository.existsByBookingId(bookingId)) {
            return;
        }
        IssuedBookingRow booking = notificationJdbcRepository.findIssuedBooking(bookingId)
                .orElseThrow(() -> new IllegalStateException("예매 " + bookingId + "가 없다"));
        List<IssuedSeatRow> seats = notificationJdbcRepository.findIssuedSeats(bookingId);
        if (seats.isEmpty()) {
            throw new IllegalStateException("예매 " + bookingId + "의 좌석이 없다");
        }
        notificationRepository.save(Notification.ticketIssued(booking.userId(), bookingId,
                TicketIssuedMessage.of(booking, seats)));
    }
}
