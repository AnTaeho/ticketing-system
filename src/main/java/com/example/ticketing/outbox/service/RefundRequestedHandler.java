package com.example.ticketing.outbox.service;

import com.example.ticketing.outbox.domain.OutboxTaskType;
import com.example.ticketing.outbox.dto.ClaimedOutboxTask;
import com.example.ticketing.payment.client.PgClient;
import com.example.ticketing.payment.client.PgResult;
import com.example.ticketing.payment.service.PaymentTransaction;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

// PG 취소는 트랜잭션 밖에서 하고, 취소가 확정된 뒤에만 결제·hold를 닫는다. 같은 orderId 취소는 PG가 같은 결과를 준다
@Slf4j
@Component
@RequiredArgsConstructor
public class RefundRequestedHandler implements OutboxHandler {

    private final PgClient pgClient;
    private final PaymentTransaction paymentTransaction;
    private final JsonMapper jsonMapper;

    @Override
    public OutboxTaskType type() {
        return OutboxTaskType.REFUND_REQUESTED;
    }

    @Override
    public void handle(ClaimedOutboxTask task) {
        JsonNode payload = jsonMapper.readTree(task.payload());
        long paymentId = payload.required("paymentId").asLong();
        String orderId = payload.required("orderId").asString();

        PgResult result = pgClient.cancel(orderId);
        switch (result.status()) {
            case CANCELLED -> paymentTransaction.completeRefund(paymentId);
            case DECLINED -> {
                // 승인으로 확인했던 결제를 PG가 거절이라고 답했다. 돈이 나가지 않은 쪽으로 확정됐으니 환불과 같게 닫는다
                log.warn("[booking outbox] 결제 {} 환불 요청에 PG가 DECLINED로 답했다. 받은 돈이 없으므로 취소로 닫는다", paymentId);
                paymentTransaction.completeRefund(paymentId);
            }
            default -> throw new IllegalStateException("PG 취소 " + result.status()
                    + (result.detail() != null ? ": " + result.detail() : ""));
        }
    }
}
