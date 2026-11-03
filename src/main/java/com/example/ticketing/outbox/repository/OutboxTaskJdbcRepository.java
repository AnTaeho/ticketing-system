package com.example.ticketing.outbox.repository;

import com.example.ticketing.outbox.domain.DueOutboxTask;
import com.example.ticketing.outbox.domain.OutboxTaskStatus;
import com.example.ticketing.outbox.domain.OutboxTaskType;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.DataClassRowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
public class OutboxTaskJdbcRepository {

    private static final String INSERT_SQL = """
            INSERT INTO outbox_task (task_type, aggregate_id, payload, status, attempt, next_attempt_at,
                                     created_at, updated_at)
            VALUES (:taskType, :aggregateId, :payload, 'PENDING', 0, NOW(6), NOW(6), NOW(6))""";

    // 처리 중 멈춘 작업(locked_until 지남)도 다시 집는다
    private static final String CLAIM_DUE_SQL = """
            SELECT outbox_task_id AS id, task_type, aggregate_id, payload, attempt FROM outbox_task
             WHERE (status = 'PENDING' AND next_attempt_at <= NOW(6))
                OR (status = 'PROCESSING' AND locked_until < NOW(6))
             ORDER BY outbox_task_id LIMIT :batch
             FOR UPDATE SKIP LOCKED""";

    private static final String LOCK_SQL = """
            UPDATE outbox_task SET status = 'PROCESSING', locked_until = :lockedUntil, updated_at = NOW(6)
             WHERE outbox_task_id IN (:ids)""";

    private static final String DONE_SQL = """
            UPDATE outbox_task SET status = 'DONE', locked_until = NULL, updated_at = NOW(6)
             WHERE outbox_task_id = :id AND status = 'PROCESSING' AND locked_until = :lockedUntil""";

    private static final String FAILED_ATTEMPT_SQL = """
            UPDATE outbox_task
               SET status = :status, attempt = :attempt,
                   next_attempt_at = DATE_ADD(NOW(6), INTERVAL :backoffSeconds SECOND),
                   locked_until = NULL, last_error = :lastError, updated_at = NOW(6)
             WHERE outbox_task_id = :id AND status = 'PROCESSING' AND locked_until = :lockedUntil""";

    private final NamedParameterJdbcTemplate jdbcTemplate;

    @Transactional(propagation = Propagation.MANDATORY)
    public void insert(OutboxTaskType type, long aggregateId, String payloadJson) {
        jdbcTemplate.update(INSERT_SQL,
                Map.of("taskType", type.name(), "aggregateId", aggregateId, "payload", payloadJson));
    }

    // 트랜잭션 밖의 SKIP LOCKED는 락이 바로 풀려 서버끼리 같은 행을 집으므로 표시와 같은 트랜잭션에서만 부른다
    @Transactional(propagation = Propagation.MANDATORY)
    public List<DueOutboxTask> claimDue(int batch) {
        return jdbcTemplate.query(CLAIM_DUE_SQL, Map.of("batch", batch), new DataClassRowMapper<>(DueOutboxTask.class));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void lock(List<Long> ids, LocalDateTime lockedUntil) {
        jdbcTemplate.update(LOCK_SQL, Map.of("ids", ids, "lockedUntil", lockedUntil));
    }

    public int processingToDone(long id, LocalDateTime lockedUntil) {
        return jdbcTemplate.update(DONE_SQL, Map.of("id", id, "lockedUntil", lockedUntil));
    }

    // 집은 그 작업(id + locked_until)일 때만 쓴다. status는 다시 시도하면 PENDING, 횟수를 다 쓰면 FAILED다
    public int markFailedAttempt(long id, LocalDateTime lockedUntil, OutboxTaskStatus status, int attempt,
                                 long backoffSeconds, String lastError) {
        return jdbcTemplate.update(FAILED_ATTEMPT_SQL, new MapSqlParameterSource()
                .addValue("id", id)
                .addValue("lockedUntil", lockedUntil)
                .addValue("status", status.name())
                .addValue("attempt", attempt)
                .addValue("backoffSeconds", backoffSeconds)
                .addValue("lastError", lastError));
    }
}
