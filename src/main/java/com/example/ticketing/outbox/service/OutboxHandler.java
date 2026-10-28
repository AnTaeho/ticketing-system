package com.example.ticketing.outbox.service;

import com.example.ticketing.outbox.domain.OutboxTaskType;
import com.example.ticketing.outbox.dto.ClaimedOutboxTask;

// 전달은 최소 한 번이라 같은 작업이 다시 와도 결과가 같아야 한다. 실패는 예외로 알린다
public interface OutboxHandler {

    OutboxTaskType type();

    void handle(ClaimedOutboxTask task);
}
