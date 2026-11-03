package com.example.ticketing.outbox.service;

import com.example.ticketing.common.config.TicketingProperties;
import com.example.ticketing.common.text.Texts;
import com.example.ticketing.common.time.DbClock;
import com.example.ticketing.outbox.domain.OutboxTask;
import com.example.ticketing.outbox.domain.OutboxTaskStatus;
import com.example.ticketing.outbox.dto.ClaimedOutboxTask;
import com.example.ticketing.outbox.repository.OutboxTaskRepository;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

// 집기(SKIP LOCKED)와 PROCESSING 표시를 한 트랜잭션에서 하고, 처리 결과(완료·실패)는 집은 그 작업(id + locked_until)일 때만 쓴다
@Component
public class OutboxTaskTransaction {

    // 1L << 63은 음수라 지수를 여기서 멈춘다
    private static final int MAX_BACKOFF_EXPONENT = Long.SIZE - 2;

    private final OutboxTaskRepository repository;
    private final DbClock dbClock;
    private final Duration lockDuration;
    private final int maxAttempts;
    private final long maxBackoffSeconds;

    public OutboxTaskTransaction(OutboxTaskRepository repository, DbClock dbClock,
                                 TicketingProperties properties) {
        this.repository = repository;
        this.dbClock = dbClock;
        this.lockDuration = properties.outbox().lockDuration();
        this.maxAttempts = properties.outbox().maxAttempts();
        this.maxBackoffSeconds = properties.outbox().maxBackoff().toSeconds();
    }

    // 집은 행은 잠겨 있으므로 엔티티로 PROCESSING을 표시하고 커밋 때 쓴다
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public List<ClaimedOutboxTask> claim(int batch) {
        List<OutboxTask> due = repository.findDueForUpdate(Limit.of(batch));
        if (due.isEmpty()) {
            return List.of();
        }
        LocalDateTime lockedUntil = dbClock.now().plus(lockDuration);
        due.forEach(task -> task.claim(lockedUntil));
        return due.stream().map(ClaimedOutboxTask::from).toList();
    }

    // false면 그 사이 잠금이 지나 다른 워커가 다시 집었다. 결과는 그쪽이 쓴다
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public boolean processingToDone(ClaimedOutboxTask task) {
        return repository.processingToDone(task.id(), task.lockedUntil()) == 1;
    }

    // 다시 해 볼 횟수가 남았으면 PENDING(2^attempt초 뒤, 최대 max-backoff), 다 썼으면 FAILED. 다른 워커가 다시 집었으면 empty
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Optional<OutboxTaskStatus> markFailedAttempt(ClaimedOutboxTask task, String error) {
        int attempt = task.attempt() + 1;
        OutboxTaskStatus status = attempt >= maxAttempts ? OutboxTaskStatus.FAILED : OutboxTaskStatus.PENDING;
        int updated = repository.markFailedAttempt(task.id(), task.lockedUntil(), status, attempt,
                backoffSeconds(attempt, maxBackoffSeconds), Texts.abbreviate(error, OutboxTask.MAX_LAST_ERROR));
        return updated == 1 ? Optional.of(status) : Optional.empty();
    }

    static long backoffSeconds(int attempt, long maxSeconds) {
        return Math.min(1L << Math.min(attempt, MAX_BACKOFF_EXPONENT), maxSeconds);
    }
}
