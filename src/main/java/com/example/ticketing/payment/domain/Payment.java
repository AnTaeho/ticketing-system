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
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

// 상태 전이는 PaymentRepository의 조건부 UPDATE로만 한다
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

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
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

    @Column(name = "last_error", length = 200)
    private String lastError;

    // 대사 대상 시각. 최종 상태가 되면 NULL
    @Column(name = "next_check_at")
    private LocalDateTime nextCheckAt;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    public static Payment requested(Long holdId, Long userId, String idempotencyKey, String orderId,
                                    int amount, LocalDateTime nextCheckAt) {
        Payment payment = new Payment();
        payment.holdId = holdId;
        payment.userId = userId;
        payment.idempotencyKey = idempotencyKey;
        payment.orderId = orderId;
        payment.amount = amount;
        payment.status = PaymentStatus.REQUESTED;
        payment.nextCheckAt = nextCheckAt;
        return payment;
    }
}
