package com.example.ticketing.ops.domain;

import java.time.LocalDateTime;

// 재시도를 다 써서 멈춘 Outbox 작업. type은 OutboxTaskType 이름이다
public record FailedTask(long id, String type, long aggregateId, int attempt, String lastError,
                         LocalDateTime updatedAt) {
}
