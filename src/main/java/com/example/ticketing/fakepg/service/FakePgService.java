package com.example.ticketing.fakepg.service;

import com.example.ticketing.common.config.TicketingProperties;
import com.example.ticketing.common.jdbc.RowCounts;
import com.example.ticketing.common.jdbc.SqlIntervals;
import com.example.ticketing.fakepg.domain.FakePgDecider;
import com.example.ticketing.fakepg.domain.FakePgOrder;
import com.example.ticketing.fakepg.domain.FakePgScenario;
import com.example.ticketing.fakepg.domain.FakePgStatus;
import com.example.ticketing.fakepg.dto.FakePgReply;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

// 지연은 PROCESSING 커밋 뒤 트랜잭션 밖에서 기다린다
@Service
public class FakePgService {

    // 상태는 앞으로만 가므로(없음 → PROCESSING/CANCELLED, APPROVED → CANCELLED) 세 번이면 결론이 난다
    private static final int MAX_CANCEL_ATTEMPTS = 3;
    private static final String PG_TX_ID_PREFIX = "fpg_";

    private final FakePgTransaction transaction;
    private final FakePgReader reader;
    private final FakePgDecider decider;
    private final FakePgSleeper sleeper;
    private final long staleMicros;

    public FakePgService(FakePgTransaction transaction, FakePgReader reader, FakePgDecider decider,
                         FakePgSleeper sleeper, TicketingProperties properties) {
        this.transaction = transaction;
        this.reader = reader;
        this.decider = decider;
        this.sleeper = sleeper;
        TicketingProperties.FakePg fakePg = properties.fakePg();
        this.staleMicros = SqlIntervals.micros(fakePg.slowDelay().plus(fakePg.staleMargin()));
    }

    public FakePgReply approve(String orderId, int amount, FakePgScenario scenario) {
        try {
            transaction.insertProcessing(orderId, amount);
        } catch (DataIntegrityViolationException e) {
            return FakePgReply.toRepeatedApprove(find(orderId).orElseThrow(() -> e));
        }

        FakePgDecider.Plan plan = decider.plan(scenario);
        pause(plan.delayMs());
        String pgTxId = plan.approve() ? PG_TX_ID_PREFIX + UUID.randomUUID().toString().replace("-", "") : null;
        int updated = plan.approve()
                ? transaction.processingToApproved(orderId, pgTxId)
                : transaction.processingToDeclined(orderId);
        if (RowCounts.changedOne(updated)) {
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
                    transaction.insertCancelled(orderId);
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
            int updated = transaction.approvedToCancelled(orderId);
            if (RowCounts.changedOne(updated)) {
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
        int updated = transaction.staleProcessingToDeclined(orderId, staleMicros);
        return RowCounts.changedOne(updated) ? find(orderId) : row;
    }

    private Optional<FakePgOrder> find(String orderId) {
        return reader.find(orderId);
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
