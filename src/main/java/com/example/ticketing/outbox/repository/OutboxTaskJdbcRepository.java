package com.example.ticketing.outbox.repository;

import com.example.ticketing.outbox.domain.DueOutboxTask;
import com.example.ticketing.outbox.domain.FailedAttempt;
import com.example.ticketing.outbox.domain.OutboxTaskType;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
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
            SELECT id, task_type, aggregate_id, payload, attempt FROM outbox_task
             WHERE (status = 'PENDING' AND next_attempt_at <= NOW(6))
                OR (status = 'PROCESSING' AND locked_until < NOW(6))
             ORDER BY id LIMIT :batch
             FOR UPDATE SKIP LOCKED""";

    private static final String LOCK_SQL = """
            UPDATE outbox_task SET status = 'PROCESSING', locked_until = :lockedUntil, updated_at = NOW(6)
             WHERE id IN (:ids)""";

    private static final String DONE_SQL = """
            UPDATE outbox_task SET status = 'DONE', locked_until = NULL, updated_at = NOW(6)
             WHERE id = :id AND status = 'PROCESSING' AND locked_until = :lockedUntil""";

    private static final String FAILED_ATTEMPT_SQL = """
            UPDATE outbox_task
               SET status = :status, attempt = :attempt,
                   next_attempt_at = DATE_ADD(NOW(6), INTERVAL :backoffSeconds SECOND),
                   locked_until = NULL, last_error = :lastError, updated_at = NOW(6)
             WHERE id = :id""";

    private static final String STILL_CLAIMED = " AND status = 'PROCESSING' AND locked_until = :lockedUntil";

    private final NamedParameterJdbcTemplate jdbcTemplate;

    @Transactional(propagation = Propagation.MANDATORY)
    public long insert(OutboxTaskType type, long aggregateId, String payloadJson) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(INSERT_SQL, new MapSqlParameterSource()
                .addValue("taskType", type.name())
                .addValue("aggregateId", aggregateId)
                .addValue("payload", payloadJson), keyHolder);
        return Objects.requireNonNull(keyHolder.getKey(), "outbox_task id").longValue();
    }

    // 트랜잭션 밖의 SKIP LOCKED는 락이 바로 풀려 서버끼리 같은 행을 집으므로 표시와 같은 트랜잭션에서만 부른다
    @Transactional(propagation = Propagation.MANDATORY)
    public List<DueOutboxTask> claimDue(int batch) {
        return jdbcTemplate.query(CLAIM_DUE_SQL, Map.of("batch", batch),
                (rs, i) -> new DueOutboxTask(rs.getLong("id"), rs.getString("task_type"), rs.getLong("aggregate_id"),
                        rs.getString("payload"), rs.getInt("attempt")));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void lock(List<Long> ids, LocalDateTime lockedUntil) {
        jdbcTemplate.update(LOCK_SQL, Map.of("ids", ids, "lockedUntil", lockedUntil));
    }

    public int markDone(long id, LocalDateTime lockedUntil) {
        return jdbcTemplate.update(DONE_SQL, Map.of("id", id, "lockedUntil", lockedUntil));
    }

    // 집은 그 작업(id + locked_until)일 때만 쓴다
    public int markFailedAttempt(long id, LocalDateTime lockedUntil, FailedAttempt failure) {
        return jdbcTemplate.update(FAILED_ATTEMPT_SQL + STILL_CLAIMED,
                params(id, failure).addValue("lockedUntil", lockedUntil));
    }

    // 집기 트랜잭션이 행 락을 쥔 채로 쓴다. 집지 않고 실패만 기록하는 행에 쓴다
    @Transactional(propagation = Propagation.MANDATORY)
    public int markFailedAttemptLocked(long id, FailedAttempt failure) {
        return jdbcTemplate.update(FAILED_ATTEMPT_SQL, params(id, failure));
    }

    private static MapSqlParameterSource params(long id, FailedAttempt failure) {
        return new MapSqlParameterSource()
                .addValue("id", id)
                .addValue("status", failure.status().name())
                .addValue("attempt", failure.attempt())
                .addValue("backoffSeconds", failure.backoffSeconds())
                .addValue("lastError", failure.lastError());
    }
}
