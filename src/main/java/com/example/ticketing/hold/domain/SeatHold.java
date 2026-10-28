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

// 상태 전이는 SeatHoldRepository의 조건부 UPDATE로만 한다
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
    private Long id;

    @Column(name = "performance_id", nullable = false)
    private Long performanceId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    // MySQL 방언은 길이 있는 CHAR를 varchar로 만들어 설계 DDL대로 char(36)을 직접 적는다
    @Column(name = "hold_token", nullable = false, columnDefinition = "char(36)")
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

    @Column(name = "pay_deadline")
    private LocalDateTime payDeadline;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    public static SeatHold held(Long performanceId, Long userId, String holdToken, int seatCount,
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
}
