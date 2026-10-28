package com.example.ticketing.outbox.service;

import com.example.ticketing.outbox.domain.OutboxTaskType;
import com.example.ticketing.outbox.dto.OutboxPayload;
import com.example.ticketing.outbox.repository.OutboxTaskJdbcRepository;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

// 상태 변경과 같은 트랜잭션에 기록해야 작업을 잃지 않는다. 트랜잭션 밖에서 부르면 예외로 막는다
@Component
@RequiredArgsConstructor
public class OutboxWriter {

    private final OutboxTaskJdbcRepository repository;
    private final JsonMapper jsonMapper;

    @Transactional(propagation = Propagation.MANDATORY)
    public long write(OutboxTaskType type, long aggregateId, OutboxPayload payload) {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(payload, "payload");
        return repository.insert(type, aggregateId, jsonMapper.writeValueAsString(payload));
    }
}
