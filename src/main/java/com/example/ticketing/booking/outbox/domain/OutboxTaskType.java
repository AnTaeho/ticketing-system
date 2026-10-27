package com.example.ticketing.booking.outbox.domain;

// aggregate_id: TICKET_ISSUED = booking_id, REFUND_REQUESTED = payment_id
public enum OutboxTaskType {
    TICKET_ISSUED,
    REFUND_REQUESTED
}
