package com.example.ticketing.payment.service;

import com.example.ticketing.common.config.TicketingProperties;
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
import java.time.Duration;
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
    private final PaymentGateReflector reflector;
    private final Duration gateKeyMargin;

    public PaymentService(PaymentTransaction paymentTransaction, PaymentReader paymentReader, PgClient pgClient,
                          SeatGateReflector gate, TicketingProperties properties) {
        this.paymentTransaction = paymentTransaction;
        this.paymentReader = paymentReader;
        this.pgClient = pgClient;
        this.gate = gate;
        this.reflector = new PaymentGateReflector(gate);
        this.gateKeyMargin = properties.payment().gateKeyMargin();
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

        // 게이트 키가 결제 중에 풀리지 않게 결제 마감 뒤까지 늘린다
        gate.extend(started.gate(), Duration.between(started.dbNow(), started.payDeadline()).plus(gateKeyMargin));

        PgResult pg = pgClient.approve(started.orderId(), started.amount(), command.pgScenario().map(Enum::name));
        PaymentSettlement settlement = switch (pg.status()) {
            case APPROVED -> paymentTransaction.confirm(started.paymentId(), pg.pgTxId());
            case DECLINED -> paymentTransaction.decline(started.paymentId());
            default -> paymentTransaction.markUnknown(started.paymentId(), unknownCause(pg));
        };
        // 승인 중 남이 가져간 좌석의 키도 이 요청이 늘려 두었으므로, 트랜잭션이 본 좌석 대신 늘린 좌석 전부에 반영한다
        reflector.reflect(settlement.withGate(started.gate()));
        return successOrThrow(settlement.result());
    }

    public PaymentResult getPayment(long userId, long paymentId) {
        return paymentReader.find(userId, paymentId);
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
            case SEAT_LOST -> throw failure(ErrorCode.SEAT_LOST_REFUNDING, paymentId);
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
