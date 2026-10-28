package com.example.ticketing.outbox.domain;

// 집기 SQL이 읽은 행. 종류는 enum으로 바꾸기 전의 DB 값 그대로다
public record DueOutboxTask(long id, String taskType, long aggregateId, String payload, int attempt) {
}
