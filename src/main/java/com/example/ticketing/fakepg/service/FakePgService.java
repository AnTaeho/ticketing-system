package com.example.ticketing.fakepg.service;

import com.example.ticketing.common.config.TicketingProperties;
import com.example.ticketing.fakepg.domain.FakePgDecider;
import com.example.ticketing.fakepg.domain.FakePgOrder;
import com.example.ticketing.fakepg.domain.FakePgScenario;
import com.example.ticketing.fakepg.domain.FakePgStatus;
import com.example.ticketing.fakepg.dto.FakePgReply;
import com.example.ticketing.fakepg.repository.FakePgOrderRepository;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

// 쓰기마다 짧은 트랜잭션을 따로 열고, 지연은 PROCESSING 커밋 뒤 트랜잭션 밖에서 기다린다
@Service
public class FakePgService {

    // 상태는 앞으로만 가므로(없음 → PROCESSING/CANCELLED, APPROVED → CANCELLED) 세 번이면 결론이 난다
    private static final int MAX_CANCEL_ATTEMPTS = 3;
    private static final String PG_TX_ID_PREFIX = "fpg_";
    private static final Duration STALE_MARGIN = Duration.ofSeconds(30);

    private final FakePgOrderRepository repository;
    private final FakePgDecider decider;
    private final FakePgSleeper sleeper;
    private final TransactionTemplate write;
    private final TransactionTemplate freshRead;
    private final long staleMicros;

    public FakePgService(FakePgOrderRepository repository, FakePgDecider decider, FakePgSleeper sleeper,
                         PlatformTransactionManager transactionManager, TicketingProperties properties) {
        this.repository = repository;
        this.decider = decider;
        this.sleeper = sleeper;
        this.staleMicros = Duration.ofMillis(properties.fakePg().slowDelayMs()).plus(STALE_MARGIN).toNanos() / 1_000;
        this.write = new TransactionTemplate(transactionManager);
        this.write.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.write.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        this.freshRead = new TransactionTemplate(transactionManager);
        this.freshRead.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.freshRead.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        this.freshRead.setReadOnly(true);
    }

    public FakePgReply approve(String orderId, int amount, FakePgScenario scenario) {
        try {
            write.executeWithoutResult(status -> repository.insertProcessing(orderId, amount));
        } catch (DataIntegrityViolationException e) {
            return FakePgReply.toRepeatedApprove(find(orderId).orElseThrow(() -> e));
        }

        FakePgDecider.Plan plan = decider.plan(scenario);
        pause(plan.delayMs());
        String pgTxId = plan.approve() ? PG_TX_ID_PREFIX + UUID.randomUUID().toString().replace("-", "") : null;
        Integer updated = write.execute(status -> plan.approve()
                ? repository.approveProcessing(orderId, pgTxId)
                : repository.declineProcessing(orderId));
        if (updated != null && updated == 1) {
            return FakePgReply.of(orderId, plan.approve() ? FakePgStatus.APPROVED : FakePgStatus.DECLINED, pgTxId);
        }
        // 지연 중에 행이 바뀌었으면 지금 행을 그대로 알린다
        return FakePgReply.toRepeatedApprove(find(orderId).orElseThrow(
                () -> new IllegalStateException("fake_pg_transaction 행이 사라졌다: " + orderId)));
    }

    public FakePgReply query(String orderId) {
        return findSettlingStale(orderId).map(FakePgReply::of).orElseGet(() -> FakePgReply.notFound(orderId));
    }

    // PROCESSING이면 바꾸지 않고 PROCESSING을 돌려준다(HTTP 409)
    public FakePgReply cancel(String orderId) {
        for (int attempt = 0; attempt < MAX_CANCEL_ATTEMPTS; attempt++) {
            Optional<FakePgOrder> row = findSettlingStale(orderId);
            if (row.isEmpty()) {
                try {
                    write.executeWithoutResult(status -> repository.insertCancelled(orderId));
                    return FakePgReply.of(orderId, FakePgStatus.CANCELLED, null);
                } catch (DataIntegrityViolationException e) {
                    // 같은 주문의 승인·취소가 먼저 넣은 행을 다시 읽어 규칙을 적용한다
                    continue;
                }
            }
            FakePgOrder tx = row.get();
            if (tx.getStatus() != FakePgStatus.APPROVED) {
                return FakePgReply.of(tx);
            }
            Integer updated = write.execute(status -> repository.cancelApproved(orderId));
            if (updated != null && updated == 1) {
                return FakePgReply.of(orderId, FakePgStatus.CANCELLED, tx.getPgTxId());
            }
        }
        throw new IllegalStateException("Fake PG 취소가 결론을 내지 못했다: " + orderId);
    }

    // 가장 느린 승인보다도 오래 PROCESSING이면 승인 시간 초과로 거절을 확정한 뒤 규칙을 적용한다
    private Optional<FakePgOrder> findSettlingStale(String orderId) {
        Optional<FakePgOrder> row = find(orderId);
        if (row.isEmpty() || row.get().getStatus() != FakePgStatus.PROCESSING) {
            return row;
        }
        Integer updated = write.execute(status -> repository.declineStaleProcessing(orderId, staleMicros));
        return updated != null && updated == 1 ? find(orderId) : row;
    }

    private Optional<FakePgOrder> find(String orderId) {
        return freshRead.execute(status -> repository.findById(orderId));
    }

    // 끊겨도 PROCESSING에 묶이지 않게 결과는 쓰고 끊김 표시만 되살린다
    private void pause(long delayMs) {
        if (delayMs <= 0) {
            return;
        }
        try {
            sleeper.sleep(delayMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
