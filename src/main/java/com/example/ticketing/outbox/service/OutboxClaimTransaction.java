package com.example.ticketing.outbox.service;

import com.example.ticketing.common.config.TicketingProperties;
import com.example.ticketing.common.time.DbClock;
import com.example.ticketing.outbox.domain.OutboxTaskStatus;
import com.example.ticketing.outbox.dto.ClaimedOutboxTask;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

// 집기와 PROCESSING 표시를 한 트랜잭션에서 한다. 결과는 집은 그 작업(id + locked_until)일 때만 쓴다
@Component
public class OutboxClaimTransaction {

    static final long MAX_BACKOFF_SECONDS = 60;
    private static final int MAX_LAST_ERROR = 500;

    // 처리 중 멈춘 작업(locked_until 지남)도 다시 집는다
    private static final String CLAIM_SQL = """
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
             WHERE id = :id AND status = 'PROCESSING' AND locked_until = :lockedUntil""";

    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final DbClock dbClock;
    private final Duration lockDuration;
    private final int maxAttempts;

    public OutboxClaimTransaction(NamedParameterJdbcTemplate jdbcTemplate, DbClock dbClock,
                                  TicketingProperties properties) {
        this.jdbcTemplate = jdbcTemplate;
        this.dbClock = dbClock;
        this.lockDuration = properties.outbox().lockDuration();
        this.maxAttempts = properties.outbox().maxAttempts();
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public List<ClaimedOutboxTask> claim(int batch) {
        List<Row> rows = jdbcTemplate.query(CLAIM_SQL, Map.of("batch", batch),
                (rs, i) -> new Row(rs.getLong("id"), rs.getString("task_type"), rs.getLong("aggregate_id"),
                        rs.getString("payload"), rs.getInt("attempt")));
        if (rows.isEmpty()) {
            return List.of();
        }
        LocalDateTime lockedUntil = dbClock.now().plus(lockDuration);
        jdbcTemplate.update(LOCK_SQL, Map.of("ids", rows.stream().map(Row::id).toList(),
                "lockedUntil", lockedUntil));
        return rows.stream()
                .map(r -> new ClaimedOutboxTask(r.id(), r.taskType(), r.aggregateId(), r.payload(), r.attempt(),
                        lockedUntil))
                .toList();
    }

    // false면 그 사이 잠금이 지나 다른 워커가 다시 집었다. 결과는 그쪽이 쓴다
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public boolean markDone(ClaimedOutboxTask task) {
        return jdbcTemplate.update(DONE_SQL, Map.of("id", task.id(), "lockedUntil", task.lockedUntil())) == 1;
    }

    // 다시 해 볼 횟수가 남았으면 PENDING(2^attempt초 뒤, 최대 60초), 다 썼으면 FAILED. 다른 워커가 다시 집었으면 empty
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Optional<OutboxTaskStatus> markFailedAttempt(ClaimedOutboxTask task, String error) {
        int attempt = task.attempt() + 1;
        OutboxTaskStatus status = attempt >= maxAttempts ? OutboxTaskStatus.FAILED : OutboxTaskStatus.PENDING;
        Map<String, Object> params = new HashMap<>();
        params.put("id", task.id());
        params.put("lockedUntil", task.lockedUntil());
        params.put("status", status.name());
        params.put("attempt", attempt);
        params.put("backoffSeconds", backoffSeconds(attempt));
        params.put("lastError", abbreviate(error));
        return jdbcTemplate.update(FAILED_ATTEMPT_SQL, params) == 1
                ? Optional.of(status)
                : Optional.empty();
    }

    static long backoffSeconds(int attempt) {
        return attempt >= 6 ? MAX_BACKOFF_SECONDS : Math.min(1L << attempt, MAX_BACKOFF_SECONDS);
    }

    private static String abbreviate(String error) {
        if (error == null) {
            return null;
        }
        return error.length() <= MAX_LAST_ERROR ? error : error.substring(0, MAX_LAST_ERROR - 1) + "…";
    }

    private record Row(long id, String taskType, long aggregateId, String payload, int attempt) {
    }
}
