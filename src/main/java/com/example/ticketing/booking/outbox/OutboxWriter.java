package com.example.ticketing.booking.outbox;

import com.example.ticketing.booking.outbox.domain.OutboxTaskType;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

// 상태 변경과 같은 트랜잭션에 기록해야 작업을 잃지 않는다. 트랜잭션 밖에서 부르면 예외로 막는다
@Component
@RequiredArgsConstructor
public class OutboxWriter {

    private static final String INSERT_SQL = """
            INSERT INTO outbox_task (task_type, aggregate_id, payload, status, attempt, next_attempt_at,
                                     created_at, updated_at)
            VALUES (?, ?, ?, 'PENDING', 0, NOW(6), NOW(6), NOW(6))""";

    private final JdbcTemplate jdbcTemplate;

    @Transactional(propagation = Propagation.MANDATORY)
    public long write(OutboxTaskType type, long aggregateId, String payloadJson) {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(payloadJson, "payloadJson");
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(INSERT_SQL, Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, type.name());
            ps.setLong(2, aggregateId);
            ps.setString(3, payloadJson);
            return ps;
        }, keyHolder);
        return Objects.requireNonNull(keyHolder.getKey(), "outbox_task id").longValue();
    }
}
