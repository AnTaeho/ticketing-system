package com.example.ticketing.outbox.domain;

// 집기 SQL이 읽은 행
public record DueOutboxTask(long id, OutboxTaskType taskType, long aggregateId, String payload, int attempt) {
}
