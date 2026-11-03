package com.example.ticketing.outbox.service;

import com.example.ticketing.outbox.domain.OutboxTask;
import com.example.ticketing.outbox.domain.OutboxTaskType;
import com.example.ticketing.outbox.dto.OutboxPayload;
import com.example.ticketing.outbox.repository.OutboxTaskRepository;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

// 상태 변경과 같은 트랜잭션에 기록해야 작업을 잃지 않는다. 트랜잭션 밖에서 부르면 예외로 막는다
@Component
@RequiredArgsConstructor
public class OutboxWriter {

    private final OutboxTaskRepository repository;
    private final JsonMapper jsonMapper;

    // dbNow는 호출한 트랜잭션이 DbClock에서 이미 읽은 시각이다. INSERT 뒤 DB 시각을 다시 읽지 않으려고 받는다
    @Transactional(propagation = Propagation.MANDATORY)
    public void write(OutboxTaskType type, long aggregateId, OutboxPayload payload, LocalDateTime dbNow) {
        repository.save(OutboxTask.pending(type, aggregateId, jsonMapper.writeValueAsString(payload), dbNow));
    }
}
