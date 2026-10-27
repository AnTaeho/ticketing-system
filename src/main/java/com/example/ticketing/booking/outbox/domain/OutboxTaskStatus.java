package com.example.ticketing.booking.outbox.domain;

public enum OutboxTaskStatus {
    PENDING,
    PROCESSING,
    DONE,
    FAILED
}
