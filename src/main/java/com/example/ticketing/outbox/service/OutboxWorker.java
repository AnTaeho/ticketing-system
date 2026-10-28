package com.example.ticketing.outbox.service;

import com.example.ticketing.common.config.TicketingProperties;
import com.example.ticketing.outbox.domain.OutboxTaskStatus;
import com.example.ticketing.outbox.dto.ClaimedOutboxTask;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

// 여러 서버가 동시에 돌아도 집기의 SKIP LOCKED로 나눠 갖는다. 핸들러는 트랜잭션 밖에서 부른다
@Slf4j
@Component
@ConditionalOnProperty(name = "booking.outbox.worker-enabled", havingValue = "true", matchIfMissing = true)
public class OutboxWorker {

    static final String METRIC = "booking.outbox.task";

    private final OutboxClaimTransaction claimTransaction;
    private final Map<String, OutboxHandler> handlers = new HashMap<>();
    private final MeterRegistry meterRegistry;
    private final int batchSize;

    public OutboxWorker(OutboxClaimTransaction claimTransaction, List<OutboxHandler> handlers,
                        TicketingProperties properties, MeterRegistry meterRegistry) {
        this.claimTransaction = claimTransaction;
        for (OutboxHandler handler : handlers) {
            if (this.handlers.put(handler.type().name(), handler) != null) {
                throw new IllegalStateException("Outbox 핸들러가 둘이다: " + handler.type());
            }
        }
        this.meterRegistry = meterRegistry;
        this.batchSize = properties.outbox().batchSize();
    }

    @Scheduled(fixedDelayString = "${booking.outbox.poll-interval-ms}")
    public void scan() {
        for (ClaimedOutboxTask task : claimTransaction.claim(batchSize)) {
            try {
                process(task);
            } catch (RuntimeException e) {
                // 결과를 못 썼어도 locked_until이 지나면 다시 집힌다
                log.warn("[booking outbox] 작업 {} 결과 반영 실패, 다음 건으로 넘어간다", task.id(), e);
            }
        }
    }

    private void process(ClaimedOutboxTask task) {
        OutboxHandler handler = handlers.get(task.taskType());
        if (handler == null) {
            fail(task, "핸들러 없음: " + task.taskType());
            return;
        }
        try {
            handler.handle(task);
        } catch (RuntimeException e) {
            log.info("[booking outbox] 작업 {}({}) 처리 실패 attempt={}: {}", task.id(), task.taskType(),
                    task.attempt() + 1, e.toString());
            fail(task, e.toString());
            return;
        }
        if (claimTransaction.markDone(task)) {
            count(task, "done");
        } else {
            log.info("[booking outbox] 작업 {}는 잠금이 지나 다른 워커가 다시 집었다. 결과는 그쪽이 쓴다", task.id());
        }
    }

    private void fail(ClaimedOutboxTask task, String error) {
        Optional<OutboxTaskStatus> status = claimTransaction.markFailedAttempt(task, error);
        if (status.isEmpty()) {
            log.info("[booking outbox] 작업 {}는 잠금이 지나 다른 워커가 다시 집었다. 실패 기록을 건너뛴다", task.id());
            return;
        }
        if (status.get() == OutboxTaskStatus.FAILED) {
            log.warn("[booking outbox] 작업 {}({}, aggregate {})가 {}번 실패해 FAILED로 멈췄다: {}", task.id(),
                    task.taskType(), task.aggregateId(), task.attempt() + 1, error);
            count(task, "failed");
        } else {
            count(task, "retry");
        }
    }

    private void count(ClaimedOutboxTask task, String result) {
        meterRegistry.counter(METRIC, "type", task.taskType(), "result", result).increment();
    }
}
