package com.example.ticketing.outbox.service;

import com.example.ticketing.common.config.TicketingProperties;
import com.example.ticketing.common.log.ThrottledWarn;
import com.example.ticketing.common.metrics.Metrics;
import com.example.ticketing.common.metrics.Metrics.OutboxResult;
import com.example.ticketing.common.metrics.Metrics.Tag;
import com.example.ticketing.outbox.domain.OutboxTaskStatus;
import com.example.ticketing.outbox.domain.OutboxTaskType;
import com.example.ticketing.outbox.dto.ClaimedOutboxTask;
import com.example.ticketing.outbox.dto.OutboxPayload;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.LongSupplier;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

// 여러 서버가 동시에 돌아도 집기의 SKIP LOCKED로 나눠 갖는다. 핸들러는 트랜잭션 밖에서 부른다
@Slf4j
@Component
@ConditionalOnProperty(name = "ticketing.outbox.worker-enabled", havingValue = "true", matchIfMissing = true)
public class OutboxDispatchScheduler {

    private final OutboxTaskTransaction taskTransaction;
    private final Map<OutboxTaskType, OutboxHandler<?>> handlers = new EnumMap<>(OutboxTaskType.class);
    private final JsonMapper payloadMapper;
    private final Map<OutboxTaskType, TypeCounters> counters = new EnumMap<>(OutboxTaskType.class);
    private final ThrottledWarn retryWarn;
    private final int batchSize;

    public OutboxDispatchScheduler(OutboxTaskTransaction taskTransaction, List<OutboxHandler<?>> handlers,
                                   JsonMapper jsonMapper, LongSupplier nanoClock, TicketingProperties properties,
                                   MeterRegistry meterRegistry) {
        this.taskTransaction = taskTransaction;
        for (OutboxHandler<?> handler : handlers) {
            if (this.handlers.put(handler.type(), handler) != null) {
                throw new IllegalStateException("Outbox 핸들러가 둘이다: " + handler.type());
            }
        }
        // 옛 행에 남은 다른 키는 무시하고, 빠진 키는 0으로 채우지 않고 실패로 돌린다
        this.payloadMapper = jsonMapper.rebuild()
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES)
                .enable(DeserializationFeature.FAIL_ON_NULL_CREATOR_PROPERTIES)
                .build();
        for (OutboxTaskType type : OutboxTaskType.values()) {
            counters.put(type, TypeCounters.register(meterRegistry, type.name()));
        }
        this.retryWarn = new ThrottledWarn(log, nanoClock);
        this.batchSize = properties.outbox().batchSize();
    }

    @Scheduled(fixedDelayString = "${ticketing.outbox.poll-interval-ms}")
    public void run() {
        for (ClaimedOutboxTask task : taskTransaction.claim(batchSize)) {
            try {
                process(task);
            } catch (RuntimeException e) {
                // 결과를 못 썼어도 locked_until이 지나면 다시 집힌다
                log.warn("[outbox] 결과 반영 실패, 다음 건으로 넘어간다 taskId={}", task.id(), e);
            }
        }
    }

    private void process(ClaimedOutboxTask task) {
        OutboxHandler<?> handler = handlers.get(task.taskType());
        if (handler == null) {
            fail(task, "핸들러 없음: " + task.taskType(), null);
            return;
        }
        try {
            dispatch(handler, task);
        } catch (RuntimeException e) {
            fail(task, e.toString(), e);
            return;
        }
        if (taskTransaction.markDone(task)) {
            counters.get(task.taskType()).done().increment();
        } else {
            log.info("[outbox] 잠금이 지나 다른 워커가 다시 집었다. 결과는 그쪽이 쓴다 taskId={}", task.id());
        }
    }

    private <P extends OutboxPayload> void dispatch(OutboxHandler<P> handler, ClaimedOutboxTask task) {
        handler.handle(payloadMapper.readValue(task.payload(), handler.payloadType()));
    }

    private void fail(ClaimedOutboxTask task, String error, RuntimeException cause) {
        Optional<OutboxTaskStatus> status = taskTransaction.markFailedAttempt(task, error);
        if (status.isEmpty()) {
            log.info("[outbox] 잠금이 지나 다른 워커가 다시 집었다. 실패 기록을 건너뛴다 taskId={}", task.id());
            return;
        }
        int attempt = task.attempt() + 1;
        if (status.get() == OutboxTaskStatus.FAILED) {
            log.warn("[outbox] 다시 시도할 횟수를 다 써 FAILED로 멈췄다 taskId={} type={} aggregateId={} attempt={} error={}",
                    task.id(), task.taskType(), task.aggregateId(), attempt, error, cause);
            counters.get(task.taskType()).failed().increment();
        } else {
            retryWarn.warn("[outbox] 처리 실패, 나중에 다시 시도한다 taskId={} type={} attempt={} error={}", task.id(),
                    task.taskType(), attempt, error);
            counters.get(task.taskType()).retry().increment();
        }
    }

    private record TypeCounters(Counter done, Counter retry, Counter failed) {

        static TypeCounters register(MeterRegistry meterRegistry, String type) {
            return new TypeCounters(counter(meterRegistry, type, OutboxResult.DONE),
                    counter(meterRegistry, type, OutboxResult.RETRY), counter(meterRegistry, type, OutboxResult.FAILED));
        }

        private static Counter counter(MeterRegistry meterRegistry, String type, String result) {
            return meterRegistry.counter(Metrics.OUTBOX_TASK, Tag.TYPE, type, Tag.RESULT, result);
        }
    }
}
