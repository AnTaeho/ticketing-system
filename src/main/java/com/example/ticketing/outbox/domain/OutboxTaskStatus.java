package com.example.ticketing.outbox.domain;

public enum OutboxTaskStatus {
    PENDING,
    PROCESSING,
    DONE,
    FAILED
}
