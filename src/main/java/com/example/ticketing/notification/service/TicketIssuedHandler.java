package com.example.ticketing.notification.service;

import com.example.ticketing.common.jdbc.ConstraintViolations;
import com.example.ticketing.notification.domain.Notification;
import com.example.ticketing.outbox.domain.OutboxTaskType;
import com.example.ticketing.outbox.dto.TicketIssuedPayload;
import com.example.ticketing.outbox.service.OutboxHandler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

// 발권 알림을 남긴다. 이미 있으면(재처리) uk_notification_booking 위반을 성공으로 본다
@Slf4j
@Component
@RequiredArgsConstructor
public class TicketIssuedHandler implements OutboxHandler<TicketIssuedPayload> {

    private final NotificationTransaction notificationTransaction;

    @Override
    public OutboxTaskType type() {
        return OutboxTaskType.TICKET_ISSUED;
    }

    @Override
    public Class<TicketIssuedPayload> payloadType() {
        return TicketIssuedPayload.class;
    }

    @Override
    public void handle(TicketIssuedPayload payload) {
        long bookingId = payload.bookingId();
        try {
            notificationTransaction.issueTicket(bookingId);
        } catch (DataIntegrityViolationException e) {
            if (!ConstraintViolations.matches(e, Notification.UK_BOOKING)) {
                throw e;
            }
            log.info("[notification] 발권 알림이 이미 있다 bookingId={}", bookingId);
        }
    }
}
