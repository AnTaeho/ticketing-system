package com.example.ticketing.outbox.dto;

import com.example.ticketing.outbox.domain.OutboxTaskType;
import java.time.LocalDateTime;

// 집은 작업. lockedUntil은 결과를 쓸 때 "아직 내가 집은 그 작업인지" 확인하는 표지다
public record ClaimedOutboxTask(long id, OutboxTaskType taskType, long aggregateId, String payload, int attempt,
                                LocalDateTime lockedUntil) {
}
