package com.example.ticketing.ops.domain;

import com.example.ticketing.outbox.domain.OutboxTaskType;
import java.time.LocalDateTime;

// 재시도를 다 써서 멈춘 Outbox 작업
public record FailedTask(long id, OutboxTaskType type, long aggregateId, int attempt, String lastError,
                         LocalDateTime updatedAt) {
}
