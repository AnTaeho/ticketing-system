package com.example.ticketing.outbox.service;

import com.example.ticketing.common.config.TicketingProperties;
import com.example.ticketing.common.jdbc.RowCounts;
import com.example.ticketing.common.text.Texts;
import com.example.ticketing.common.time.DbClock;
import com.example.ticketing.outbox.domain.DueOutboxTask;
import com.example.ticketing.outbox.domain.FailedAttempt;
import com.example.ticketing.outbox.domain.OutboxTask;
import com.example.ticketing.outbox.domain.OutboxTaskStatus;
import com.example.ticketing.outbox.domain.OutboxTaskType;
import com.example.ticketing.outbox.dto.ClaimedOutboxTask;
import com.example.ticketing.outbox.repository.OutboxTaskJdbcRepository;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

// 집기와 PROCESSING 표시를 한 트랜잭션에서 하고, 처리 결과(완료·실패)는 집은 그 작업(id + locked_until)일 때만 쓴다
@Slf4j
@Component
public class OutboxTaskTransaction {

    // 1L << 63은 음수라 지수를 여기서 멈춘다
    private static final int MAX_BACKOFF_EXPONENT = Long.SIZE - 2;

    private final OutboxTaskJdbcRepository repository;
    private final DbClock dbClock;
    private final Duration lockDuration;
    private final int maxAttempts;
    private final long maxBackoffSeconds;

    public OutboxTaskTransaction(OutboxTaskJdbcRepository repository, DbClock dbClock,
                                 TicketingProperties properties) {
        this.repository = repository;
        this.dbClock = dbClock;
        this.lockDuration = properties.outbox().lockDuration();
        this.maxAttempts = properties.outbox().maxAttempts();
        this.maxBackoffSeconds = properties.outbox().maxBackoff().toSeconds();
    }

    // 모르는 종류는 집지 않고 핸들러가 없는 작업과 같게 실패로 기록한다
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public List<ClaimedOutboxTask> claim(int batch) {
        List<DueOutboxTask> due = repository.claimDue(batch);
        List<DueOutboxTask> known = new ArrayList<>();
        for (DueOutboxTask task : due) {
            if (OutboxTaskType.parse(task.taskType()).isPresent()) {
                known.add(task);
            } else {
                FailedAttempt failure = failedAttempt(task.attempt(), "핸들러 없음: " + task.taskType());
                repository.markFailedAttemptLocked(task.id(), failure);
                log.warn("[outbox] 모르는 작업 종류라 실패로 기록했다 taskId={} type={} status={}", task.id(),
                        task.taskType(), failure.status());
            }
        }
        if (known.isEmpty()) {
            return List.of();
        }
        LocalDateTime lockedUntil = dbClock.now().plus(lockDuration);
        repository.lock(known.stream().map(DueOutboxTask::id).toList(), lockedUntil);
        return known.stream()
                .map(t -> new ClaimedOutboxTask(t.id(), OutboxTaskType.valueOf(t.taskType()), t.aggregateId(),
                        t.payload(), t.attempt(), lockedUntil))
                .toList();
    }

    // false면 그 사이 잠금이 지나 다른 워커가 다시 집었다. 결과는 그쪽이 쓴다
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public boolean markDone(ClaimedOutboxTask task) {
        return RowCounts.changedOne(repository.markDone(task.id(), task.lockedUntil()));
    }

    // 다시 해 볼 횟수가 남았으면 PENDING(2^attempt초 뒤, 최대 max-backoff), 다 썼으면 FAILED. 다른 워커가 다시 집었으면 empty
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Optional<OutboxTaskStatus> markFailedAttempt(ClaimedOutboxTask task, String error) {
        FailedAttempt failure = failedAttempt(task.attempt(), error);
        return RowCounts.changedOne(repository.markFailedAttempt(task.id(), task.lockedUntil(), failure))
                ? Optional.of(failure.status())
                : Optional.empty();
    }

    private FailedAttempt failedAttempt(int previousAttempts, String error) {
        int attempt = previousAttempts + 1;
        OutboxTaskStatus status = attempt >= maxAttempts ? OutboxTaskStatus.FAILED : OutboxTaskStatus.PENDING;
        return new FailedAttempt(status, attempt, backoffSeconds(attempt, maxBackoffSeconds),
                Texts.abbreviate(error, OutboxTask.MAX_LAST_ERROR));
    }

    static long backoffSeconds(int attempt, long maxSeconds) {
        return Math.min(1L << Math.min(attempt, MAX_BACKOFF_EXPONENT), maxSeconds);
    }
}
