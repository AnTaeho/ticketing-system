package com.example.ticketing.ops.domain;

import com.example.ticketing.outbox.domain.OutboxTaskStatus;
import com.example.ticketing.outbox.domain.OutboxTaskType;

public record OutboxStatusCount(OutboxTaskType taskType, OutboxTaskStatus status, long count) {
}
