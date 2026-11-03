package com.example.ticketing.notification.service;

import com.example.ticketing.notification.domain.IssuedSeat;
import com.example.ticketing.notification.domain.IssuedTicket;
import com.example.ticketing.notification.domain.Notification;
import com.example.ticketing.notification.domain.TicketIssuedMessage;
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

    // 이미 있으면 아무것도 하지 않는다. 동시에 처리한 쪽과 겹치면 uk_notification_booking 위반 예외로 끝난다
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void issueTicket(long bookingId) {
        if (notificationRepository.existsByBookingId(bookingId)) {
            return;
        }
        IssuedTicket ticket = notificationRepository.findIssuedTicket(bookingId)
                .orElseThrow(() -> new IllegalStateException("예매 " + bookingId + "가 없다"));
        List<IssuedSeat> seats = notificationRepository.findIssuedSeats(bookingId);
        if (seats.isEmpty()) {
            throw new IllegalStateException("예매 " + bookingId + "의 좌석이 없다");
        }
        notificationRepository.save(Notification.ticketIssued(ticket.userId(), bookingId,
                TicketIssuedMessage.of(ticket, seats)));
    }
}
