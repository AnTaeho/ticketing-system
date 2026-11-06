package com.example.ticketing.hold.domain;

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

// 상태 전이 메서드는 이 행을 비관적 락으로 잠근 트랜잭션에서만 부른다
// 여러 행을 한 번에 바꾸는 만료는 SeatHoldRepository의 조건부 UPDATE다
@Entity
@Getter
@EntityListeners(AuditingEntityListener.class)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "seat_hold",
        uniqueConstraints = @UniqueConstraint(name = "uk_hold_token", columnNames = "hold_token"),
        indexes = {
                @Index(name = "idx_hold_expiry", columnList = "status, expires_at"),
                @Index(name = "idx_hold_user", columnList = "user_id, performance_id, status")
        })
public class SeatHold {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "hold_id")
    private Long id;

    @Column(name = "performance_id", nullable = false)
    private Long performanceId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    // MySQL 방언은 길이 있는 CHAR를 varchar로 만들어 char(36)을 직접 적는다
    // length가 없으면 스키마 update가 기본 길이 255와 비교해 매번 ALTER한다
    @Column(name = "hold_token", nullable = false, length = 36, columnDefinition = "char(36)")
    private String holdToken;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 12)
    private HoldStatus status;

    @Column(name = "seat_count", nullable = false)
    private int seatCount;

    @Column(name = "total_amount", nullable = false)
    private int totalAmount;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    public static SeatHold held(Long userId, Long performanceId, String holdToken, int seatCount,
                                int totalAmount, LocalDateTime expiresAt) {
        SeatHold hold = new SeatHold();
        hold.performanceId = performanceId;
        hold.userId = userId;
        hold.holdToken = holdToken;
        hold.status = HoldStatus.HELD;
        hold.seatCount = seatCount;
        hold.totalAmount = totalAmount;
        hold.expiresAt = expiresAt;
        return hold;
    }

    public boolean isExpiredAt(LocalDateTime dbNow) {
        return !expiresAt.isAfter(dbNow);
    }

    public void heldToReleased() {
        moveTo(HoldStatus.HELD, HoldStatus.RELEASED);
    }

    public void heldToPaying(LocalDateTime dbNow) {
        if (isExpiredAt(dbNow)) {
            throw new IllegalStateException("hold " + id + "는 " + expiresAt + "에 만료돼 결제로 넘길 수 없다");
        }
        moveTo(HoldStatus.HELD, HoldStatus.PAYING);
    }

    public void payingToConfirmed() {
        moveTo(HoldStatus.PAYING, HoldStatus.CONFIRMED);
    }

    // PG 거절·망취소 확정 때만 부른다. 사용자 취소는 heldToReleased다
    public void payingToReleased() {
        moveTo(HoldStatus.PAYING, HoldStatus.RELEASED);
    }

    private void moveTo(HoldStatus from, HoldStatus to) {
        if (status != from) {
            throw new IllegalStateException("hold " + id + "는 " + status + "라 " + from + " → " + to + "로 바꿀 수 없다");
        }
        status = to;
    }
}
