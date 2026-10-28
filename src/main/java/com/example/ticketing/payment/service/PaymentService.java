package com.example.ticketing.payment.service;

import com.example.ticketing.common.config.TicketingProperties;
import com.example.ticketing.common.error.ApiException;
import com.example.ticketing.common.error.ErrorCode;
import com.example.ticketing.hold.repository.SeatGateRedisStore;
import com.example.ticketing.payment.client.PgClient;
import com.example.ticketing.payment.client.PgResult;
import com.example.ticketing.payment.client.PgScenario;
import com.example.ticketing.payment.domain.Payment;
import com.example.ticketing.payment.dto.PaymentResponse;
import com.example.ticketing.payment.dto.PaymentSettlement;
import com.example.ticketing.payment.dto.PaymentStatusView;
import com.example.ticketing.payment.dto.StartResult;
import com.example.ticketing.payment.dto.StartResult.Existing;
import com.example.ticketing.payment.dto.StartResult.Started;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

// 트랜잭션을 열지 않는다. PG 호출과 Redis 반영은 트랜잭션 메서드가 커밋하고 돌아온 뒤에 한다
@Slf4j
@Service
public class PaymentService {

    static final int MAX_IDEMPOTENCY_KEY_LENGTH = 64;

    private final PaymentTransaction paymentTransaction;
    private final PgClient pgClient;
    private final SeatGateRedisStore gate;
    private final boolean gateEnabled;
    private final Duration gateKeyMargin;
    private final PaymentGateReflector reflector;

    public PaymentService(PaymentTransaction paymentTransaction, PgClient pgClient,
                          SeatGateRedisStore gate, TicketingProperties properties) {
        this.paymentTransaction = paymentTransaction;
        this.pgClient = pgClient;
        this.gate = gate;
        this.gateEnabled = properties.gate().enabled();
        this.gateKeyMargin = properties.payment().gateKeyMargin();
        this.reflector = new PaymentGateReflector(gate, gateEnabled);
    }

    public PaymentResponse pay(long userId, long holdId, String idempotencyKey, Optional<PgScenario> scenario) {
        String key = checkIdempotencyKey(idempotencyKey);
        StartResult start = startOrRecover(userId, holdId, key);
        if (start instanceof Existing existing) {
            return respond(existing.settlement());
        }
        Started started = (Started) start;

        // 게이트 키가 결제 중에 풀리지 않게 결제 마감 뒤까지 늘린다
        if (gateEnabled) {
            Duration ttl = Duration.between(started.dbNow(), started.payDeadline()).plus(gateKeyMargin);
            gate.extendQuietly(started.performanceId(), userId, started.seatIds(), started.holdToken(), ttl);
        }

        PgResult pg = pgClient.approve(started.orderId(), started.amount(), scenario);
        PaymentSettlement settlement = switch (pg.status()) {
            case APPROVED -> paymentTransaction.confirm(started.paymentId(), pg.pgTxId());
            case DECLINED -> paymentTransaction.decline(started.paymentId());
            default -> paymentTransaction.markUnknown(started.paymentId(), unknownDetail(pg));
        };
        reflector.reflect(settlement, started.seatIds());
        return respond(settlement);
    }

    public PaymentStatusView getPayment(long userId, long paymentId) {
        PaymentSettlement settlement = paymentTransaction.view(userId, paymentId);
        return new PaymentStatusView(settlement.paymentId(), settlement.paymentStatus(), settlement.holdStatus(),
                settlement.bookingId(), settlement.bookingNumber());
    }

    private PaymentResponse respond(PaymentSettlement settlement) {
        long paymentId = settlement.paymentId();
        return switch (settlement.outcome()) {
            case PROCESSING -> PaymentResponse.pending(paymentId, PaymentResponse.Status.PROCESSING);
            case UNKNOWN -> PaymentResponse.pending(paymentId, PaymentResponse.Status.UNKNOWN);
            case CONFIRMED -> PaymentResponse.confirmed(paymentId, settlement.bookingId(), settlement.bookingNumber());
            case SEAT_LOST -> throw failure(ErrorCode.SEAT_LOST_REFUNDING, paymentId);
            case DECLINED -> throw failure(ErrorCode.PAYMENT_DECLINED, paymentId);
            case CANCELLED -> throw failure(ErrorCode.PAYMENT_CANCELLED, paymentId);
        };
    }

    // 조회·락으로 막지 못한 경합이 유니크 제약에 걸리면 새 트랜잭션으로 다시 읽어 같은 결과를 돌려준다
    private StartResult startOrRecover(long userId, long holdId, String key) {
        try {
            return paymentTransaction.start(userId, holdId, key);
        } catch (DataIntegrityViolationException e) {
            String violated = violatedConstraint(e);
            boolean idempotency = violated.contains(Payment.UK_IDEMPOTENCY);
            if (!idempotency && !violated.contains(Payment.UK_HOLD)) {
                throw e;
            }
            log.info("[booking payment] 결제 시작 유니크 위반 constraint={}, holdId={}", violated, holdId);
            return paymentTransaction.findAfterDuplicate(userId, holdId, key, idempotency).orElseThrow(() -> e);
        }
    }

    static String violatedConstraint(DataIntegrityViolationException e) {
        StringBuilder found = new StringBuilder();
        for (Throwable cause = e; cause != null; cause = cause.getCause()) {
            if (cause instanceof ConstraintViolationException violation && violation.getConstraintName() != null) {
                found.append(violation.getConstraintName()).append(' ');
            }
            if (cause.getMessage() != null) {
                found.append(cause.getMessage()).append(' ');
            }
            if (cause.getCause() == cause) {
                break;
            }
        }
        return found.toString();
    }

    private static String checkIdempotencyKey(String key) {
        if (key == null || key.isBlank()) {
            throw new ApiException(ErrorCode.IDEMPOTENCY_KEY_REQUIRED);
        }
        String trimmed = key.trim();
        if (trimmed.length() > MAX_IDEMPOTENCY_KEY_LENGTH) {
            throw new ApiException(ErrorCode.INVALID_REQUEST,
                    Map.of("header", "Idempotency-Key", "maxLength", MAX_IDEMPOTENCY_KEY_LENGTH));
        }
        return trimmed;
    }

    private static String unknownDetail(PgResult pg) {
        return pg.detail() != null ? pg.detail() : "PG 승인 응답 " + pg.status();
    }

    private static ApiException failure(ErrorCode code, long paymentId) {
        return new ApiException(code, Map.of("paymentId", paymentId));
    }
}
