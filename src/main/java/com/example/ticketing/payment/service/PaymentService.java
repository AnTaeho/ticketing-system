package com.example.ticketing.payment.service;

import com.example.ticketing.common.error.ApiException;
import com.example.ticketing.common.error.ErrorCode;
import com.example.ticketing.common.jdbc.ConstraintViolations;
import com.example.ticketing.hold.service.SeatGateReflector;
import com.example.ticketing.payment.client.PgClient;
import com.example.ticketing.payment.client.PgResult;
import com.example.ticketing.payment.domain.Payment;
import com.example.ticketing.payment.domain.PaymentStatus;
import com.example.ticketing.payment.dto.PayCommand;
import com.example.ticketing.payment.dto.PaymentResult;
import com.example.ticketing.payment.dto.PaymentSettlement;
import com.example.ticketing.payment.dto.PaymentStart;
import com.example.ticketing.payment.dto.PaymentStart.Existing;
import com.example.ticketing.payment.dto.PaymentStart.OtherKey;
import com.example.ticketing.payment.dto.PaymentStart.Started;
import java.util.Map;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

// 트랜잭션을 열지 않는다. PG 호출과 Redis 반영은 트랜잭션 메서드가 커밋하고 돌아온 뒤에 한다
@Slf4j
@Service
public class PaymentService {

    private final PaymentTransaction paymentTransaction;
    private final PaymentReader paymentReader;
    private final PgClient pgClient;
    private final SeatGateReflector gate;

    public PaymentService(PaymentTransaction paymentTransaction, PaymentReader paymentReader, PgClient pgClient,
                          SeatGateReflector gate) {
        this.paymentTransaction = paymentTransaction;
        this.paymentReader = paymentReader;
        this.pgClient = pgClient;
        this.gate = gate;
    }

    public PaymentResult pay(PayCommand command) {
        PaymentStart start = startOrRecover(command);
        if (start instanceof Existing existing) {
            return successOrThrow(existing.result());
        }
        if (start instanceof OtherKey otherKey) {
            throw otherPaymentOnHold(command.holdId(), otherKey.other());
        }
        Started started = (Started) start;

        PgResult pg = pgClient.approve(started.orderId(), started.amount(), command.pgScenario().map(Enum::name));
        PaymentSettlement settlement = switch (pg.status()) {
            case APPROVED -> paymentTransaction.openToApproved(started.paymentId(), pg.pgTxId());
            case DECLINED -> paymentTransaction.openToDeclined(started.paymentId());
            default -> paymentTransaction.requestedToUnknown(started.paymentId(), unknownCause(pg));
        };
        settlement.gateToRelease().ifPresent(gate::release);
        return successOrThrow(settlement.result());
    }

    // 조회·락으로 막지 못한 경합이 유니크 제약에 걸리면 새 트랜잭션으로 다시 읽어 같은 결과를 돌려준다
    private PaymentStart startOrRecover(PayCommand command) {
        long userId = command.userId();
        long holdId = command.holdId();
        String key = command.idempotencyKey();
        try {
            return paymentTransaction.start(userId, holdId, key);
        } catch (DataIntegrityViolationException e) {
            boolean sameKey = ConstraintViolations.matches(e, Payment.UK_IDEMPOTENCY);
            if (!sameKey && !ConstraintViolations.matches(e, Payment.UK_HOLD)) {
                throw e;
            }
            log.info("[payment] 결제 시작 유니크 위반 constraint={} holdId={}",
                    sameKey ? Payment.UK_IDEMPOTENCY : Payment.UK_HOLD, holdId);
            Optional<PaymentStart> found = sameKey
                    ? paymentReader.findSameKeyAfterDuplicate(userId, key)
                    : paymentReader.findSameHoldAfterDuplicate(userId, holdId, key);
            return found.orElseThrow(() -> e);
        }
    }

    // 결과를 아직 모르면 202로, 실패로 끝났으면 402·409 예외로 답한다
    private static PaymentResult successOrThrow(PaymentResult result) {
        long paymentId = result.paymentId();
        return switch (result.outcome()) {
            case PROCESSING, UNKNOWN, CONFIRMED -> result;
            case DECLINED -> throw failure(ErrorCode.PAYMENT_DECLINED, paymentId);
            case CANCELLED -> throw failure(ErrorCode.PAYMENT_CANCELLED, paymentId);
        };
    }

    // 다른 키의 결제가 이미 끝나 자리가 풀렸으면 다시 고르게 하고, 아직 진행 중이면 다른 화면에서 결제 중이라고 알린다
    private static ApiException otherPaymentOnHold(long holdId, PaymentResult other) {
        if (other.paymentStatus() == PaymentStatus.DECLINED || other.paymentStatus() == PaymentStatus.CANCELLED) {
            return new ApiException(ErrorCode.HOLD_NOT_PAYABLE,
                    Map.of("holdId", holdId, "status", other.holdStatus().name()));
        }
        return new ApiException(ErrorCode.ALREADY_PAYING, Map.of("holdId", holdId));
    }

    // 승인 결과를 모를 때는 원인만 남긴다. 원인이 없으면 받은 상태를 적는다
    private static String unknownCause(PgResult pg) {
        return pg.detail() != null ? pg.detail() : pg.describe("PG 승인 응답");
    }

    private static ApiException failure(ErrorCode code, long paymentId) {
        return new ApiException(code, Map.of("paymentId", paymentId));
    }
}
