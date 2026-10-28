package com.example.ticketing.ops.domain;

import java.time.LocalDateTime;

public record FailedTaskRow(long id, String taskType, long aggregateId, int attempt, String lastError,
                            LocalDateTime updatedAt) {
}
