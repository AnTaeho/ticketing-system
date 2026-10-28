package com.example.ticketing.payment.service;

import com.example.ticketing.outbox.domain.OutboxTaskType;
import com.example.ticketing.outbox.dto.RefundRequestedPayload;
import com.example.ticketing.outbox.service.OutboxHandler;
import com.example.ticketing.payment.client.PgClient;
import com.example.ticketing.payment.client.PgResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

// PG 취소는 트랜잭션 밖에서 하고, 취소가 확정된 뒤에만 결제·hold를 닫는다. 같은 orderId 취소는 PG가 같은 결과를 준다
@Slf4j
@Component
@RequiredArgsConstructor
public class RefundRequestedHandler implements OutboxHandler<RefundRequestedPayload> {

    private final PgClient pgClient;
    private final PaymentTransaction paymentTransaction;

    @Override
    public OutboxTaskType type() {
        return OutboxTaskType.REFUND_REQUESTED;
    }

    @Override
    public Class<RefundRequestedPayload> payloadType() {
        return RefundRequestedPayload.class;
    }

    @Override
    public void handle(RefundRequestedPayload payload) {
        long paymentId = payload.paymentId();
        PgResult result = pgClient.cancel(payload.orderId());
        switch (result.status()) {
            case CANCELLED -> paymentTransaction.completeRefund(paymentId);
            case DECLINED -> {
                // 승인으로 확인했던 결제를 PG가 거절이라고 답했다. 돈이 나가지 않은 쪽으로 확정됐으니 환불과 같게 닫는다
                log.warn("[payment] 환불 요청에 PG가 DECLINED로 답해 받은 돈이 없는 것으로 보고 취소로 닫는다 paymentId={}",
                        paymentId);
                paymentTransaction.completeRefund(paymentId);
            }
            default -> throw new IllegalStateException(result.describe("PG 취소"));
        }
    }
}
