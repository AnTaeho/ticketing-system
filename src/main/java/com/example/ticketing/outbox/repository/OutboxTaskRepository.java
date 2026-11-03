package com.example.ticketing.outbox.repository;

import com.example.ticketing.outbox.domain.OutboxTask;
import com.example.ticketing.outbox.domain.OutboxTaskStatus;
import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

// 결과 반영은 잠금 기한이 지나 다른 워커가 다시 집었을 수 있어 집은 그 작업(id + locked_until)일 때만 조건부로 쓴다
public interface OutboxTaskRepository extends JpaRepository<OutboxTask, Long> {

    // 처리 중 멈춘 작업(locked_until 지남)도 다시 집는다. 트랜잭션 밖의 SKIP LOCKED는 락이 바로 풀려 서버끼리 같은 행을 집는다
    @Transactional(propagation = Propagation.MANDATORY)
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2"))
    @Query("""
            select o from OutboxTask o
             where (o.status = PENDING and o.nextAttemptAt <= current_timestamp)
                or (o.status = PROCESSING and o.lockedUntil < current_timestamp)
             order by o.id""")
    List<OutboxTask> findDueForUpdate(Limit limit);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update OutboxTask o set o.status = DONE, o.lockedUntil = null, o.updatedAt = current_timestamp
             where o.id = :id and o.status = PROCESSING and o.lockedUntil = :lockedUntil""")
    int processingToDone(@Param("id") long id, @Param("lockedUntil") LocalDateTime lockedUntil);

    // status는 다시 시도하면 PENDING, 횟수를 다 쓰면 FAILED다
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update OutboxTask o
               set o.status = :status, o.attempt = :attempt,
                   o.nextAttemptAt = current_timestamp + :backoffSeconds second,
                   o.lockedUntil = null, o.lastError = :lastError, o.updatedAt = current_timestamp
             where o.id = :id and o.status = PROCESSING and o.lockedUntil = :lockedUntil""")
    int markFailedAttempt(@Param("id") long id, @Param("lockedUntil") LocalDateTime lockedUntil,
                          @Param("status") OutboxTaskStatus status, @Param("attempt") int attempt,
                          @Param("backoffSeconds") long backoffSeconds, @Param("lastError") String lastError);
}
