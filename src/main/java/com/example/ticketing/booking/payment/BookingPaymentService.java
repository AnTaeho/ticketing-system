package com.example.ticketing.booking.payment;

import com.example.ticketing.booking.BookingProperties;
import com.example.ticketing.booking.common.BookingErrorCode;
import com.example.ticketing.booking.common.BookingException;
import com.example.ticketing.booking.hold.gate.RedisSeatGate;
import com.example.ticketing.booking.payment.StartResult.Existing;
import com.example.ticketing.booking.payment.StartResult.Started;
import com.example.ticketing.booking.payment.domain.BookingPayment;
import com.example.ticketing.booking.payment.dto.PaymentResponse;
import com.example.ticketing.booking.payment.dto.PaymentStatusView;
import com.example.ticketing.booking.payment.pg.PgClient;
import com.example.ticketing.booking.payment.pg.PgResult;
import com.example.ticketing.booking.payment.pg.PgScenario;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

// 트랜잭션을 열지 않는다. PG 호출과 Redis 반영은 트랜잭션 메서드가 커밋하고 돌아온 뒤에 한다
@Slf4j
@Service
public class BookingPaymentService {

    static final int MAX_IDEMPOTENCY_KEY_LENGTH = 64;
    private static final Duration GATE_KEY_MARGIN = Duration.ofMinutes(1);

    private final BookingPaymentTransaction paymentTransaction;
    private final PgClient pgClient;
    private final RedisSeatGate gate;
    private final boolean gateEnabled;

    public BookingPaymentService(BookingPaymentTransaction paymentTransaction, PgClient pgClient,
                                 RedisSeatGate gate, BookingProperties properties) {
        this.paymentTransaction = paymentTransaction;
        this.pgClient = pgClient;
        this.gate = gate;
        this.gateEnabled = properties.gate().enabled();
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
            Duration ttl = Duration.between(started.dbNow(), started.payDeadline()).plus(GATE_KEY_MARGIN);
            gate.extendQuietly(started.performanceId(), userId, started.seatIds(), started.holdToken(), ttl);
        }

        PgResult pg = pgClient.approve(started.orderId(), started.amount(), scenario);
        PaymentSettlement settlement = switch (pg.status()) {
            case APPROVED -> paymentTransaction.confirm(started.paymentId(), pg.pgTxId());
            case DECLINED -> paymentTransaction.decline(started.paymentId());
            default -> paymentTransaction.markUnknown(started.paymentId(), unknownDetail(pg));
        };
        reflect(settlement, started.seatIds());
        return respond(settlement);
    }

    public PaymentStatusView getPayment(long userId, long paymentId) {
        PaymentSettlement settlement = paymentTransaction.view(userId, paymentId);
        return new PaymentStatusView(settlement.paymentId(), settlement.paymentStatus(), settlement.holdStatus(),
                settlement.bookingId(), settlement.bookingNumber());
    }

    // 이번 호출이 상태를 바꿨을 때만 반영한다. 토큰이 같은 키만 건드리므로 시작 때 좌석 전체를 넘겨도 안전하다
    private void reflect(PaymentSettlement settlement, List<Long> seatIds) {
        if (!gateEnabled || !settlement.applied()) {
            return;
        }
        switch (settlement.outcome()) {
            case CONFIRMED -> gate.confirmQuietly(settlement.performanceId(), settlement.userId(), seatIds,
                    settlement.holdToken(), settlement.gateCloseAt());
            case SEAT_LOST, DECLINED, CANCELLED -> gate.releaseQuietly(settlement.performanceId(),
                    settlement.userId(), seatIds, settlement.holdToken());
            default -> {
            }
        }
    }

    private PaymentResponse respond(PaymentSettlement settlement) {
        long paymentId = settlement.paymentId();
        return switch (settlement.outcome()) {
            case PROCESSING -> PaymentResponse.pending(paymentId, PaymentResponse.Status.PROCESSING);
            case UNKNOWN -> PaymentResponse.pending(paymentId, PaymentResponse.Status.UNKNOWN);
            case CONFIRMED -> PaymentResponse.confirmed(paymentId, settlement.bookingId(), settlement.bookingNumber());
            case SEAT_LOST -> throw failure(BookingErrorCode.SEAT_LOST_REFUNDING, paymentId);
            case DECLINED -> throw failure(BookingErrorCode.PAYMENT_DECLINED, paymentId);
            case CANCELLED -> throw failure(BookingErrorCode.PAYMENT_CANCELLED, paymentId);
        };
    }

    // 조회·락으로 막지 못한 경합이 유니크 제약에 걸리면 새 트랜잭션으로 다시 읽어 같은 결과를 돌려준다
    private StartResult startOrRecover(long userId, long holdId, String key) {
        try {
            return paymentTransaction.start(userId, holdId, key);
        } catch (DataIntegrityViolationException e) {
            String violated = violatedConstraint(e);
            boolean idempotency = violated.contains(BookingPayment.UK_IDEMPOTENCY);
            if (!idempotency && !violated.contains(BookingPayment.UK_HOLD)) {
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
            throw new BookingException(BookingErrorCode.IDEMPOTENCY_KEY_REQUIRED);
        }
        String trimmed = key.trim();
        if (trimmed.length() > MAX_IDEMPOTENCY_KEY_LENGTH) {
            throw new BookingException(BookingErrorCode.INVALID_REQUEST,
                    Map.of("header", "Idempotency-Key", "maxLength", MAX_IDEMPOTENCY_KEY_LENGTH));
        }
        return trimmed;
    }

    private static String unknownDetail(PgResult pg) {
        return pg.detail() != null ? pg.detail() : "PG 승인 응답 " + pg.status();
    }

    private static BookingException failure(BookingErrorCode code, long paymentId) {
        return new BookingException(code, Map.of("paymentId", paymentId));
    }
}
