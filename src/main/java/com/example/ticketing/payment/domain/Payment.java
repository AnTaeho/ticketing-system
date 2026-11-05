package com.example.ticketing.payment.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

// 상태 전이 메서드는 hold와 이 결제 행을 비관적 락으로 잠근 트랜잭션에서만 부른다. 잠그지 않고 다투는 전이(결과 모름, 대사 일정)는 PaymentRepository의 조건부 UPDATE다
@Entity
@Getter
@EntityListeners(AuditingEntityListener.class)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "booking_payment",
        uniqueConstraints = {
                @UniqueConstraint(name = Payment.UK_HOLD, columnNames = "hold_id"),
                @UniqueConstraint(name = Payment.UK_IDEMPOTENCY, columnNames = {"user_id", "idempotency_key"}),
                @UniqueConstraint(name = "uk_payment_order", columnNames = "order_id")
        },
        indexes = @Index(name = "idx_payment_check", columnList = "status, next_check_at"))
public class Payment {

    public static final String UK_HOLD = "uk_payment_hold";
    public static final String UK_IDEMPOTENCY = "uk_payment_idem";
    public static final int MAX_LAST_ERROR = 200;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "payment_id")
    private Long id;

    @Column(name = "hold_id", nullable = false)
    private Long holdId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "idempotency_key", nullable = false, length = 64)
    private String idempotencyKey;

    @Column(name = "order_id", nullable = false, length = 40)
    private String orderId;

    @Column(nullable = false)
    private int amount;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 12)
    private PaymentStatus status;

    @Column(name = "pg_tx_id", length = 64)
    private String pgTxId;

    @Column(name = "last_error", length = MAX_LAST_ERROR)
    private String lastError;

    // 대사 대상 시각. 최종 상태가 되면 NULL
    @Column(name = "next_check_at")
    private LocalDateTime nextCheckAt;

    // 호출자가 같은 트랜잭션에서 읽은 DB 시각을 넣는다. 대사의 망취소 판정이 DB 시각과 비교하므로 JVM 시계를 쓰면 어긋난다
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    public static Payment requested(Long holdId, Long userId, String idempotencyKey, String orderId,
                                    int amount, LocalDateTime createdAt, LocalDateTime nextCheckAt) {
        Payment payment = new Payment();
        payment.holdId = holdId;
        payment.userId = userId;
        payment.idempotencyKey = idempotencyKey;
        payment.orderId = orderId;
        payment.amount = amount;
        payment.status = PaymentStatus.REQUESTED;
        payment.createdAt = createdAt;
        payment.nextCheckAt = nextCheckAt;
        return payment;
    }

    public void openToApproved(String pgTxId) {
        settle(PaymentStatus.APPROVED);
        this.pgTxId = pgTxId;
    }

    public void openToDeclined() {
        settle(PaymentStatus.DECLINED);
    }

    public void openToCancelled() {
        settle(PaymentStatus.CANCELLED);
    }

    // 승인된 결제의 환불이 PG에서 확정됐을 때만 부른다
    public void approvedToCancelled() {
        if (status != PaymentStatus.APPROVED) {
            throw invalidMove(PaymentStatus.CANCELLED);
        }
        status = PaymentStatus.CANCELLED;
    }

    // 결론이 나면 대사 대상에서 빠진다
    private void settle(PaymentStatus to) {
        if (!status.isOpen()) {
            throw invalidMove(to);
        }
        status = to;
        nextCheckAt = null;
    }

    private IllegalStateException invalidMove(PaymentStatus to) {
        return new IllegalStateException("결제 " + id + "는 " + status + "라 " + to + "로 바꿀 수 없다");
    }
}
