package com.example.ticketing.outbox.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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

// 행은 OutboxTaskJdbcRepository의 native INSERT와 조건부 UPDATE로만 쓴다
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "outbox_task",
        uniqueConstraints = @UniqueConstraint(name = "uk_outbox_dedup", columnNames = {"task_type", "aggregate_id"}),
        indexes = @Index(name = "idx_outbox_poll", columnList = "status, next_attempt_at"))
public class OutboxTask {

    public static final int MAX_LAST_ERROR = 500;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "task_type", nullable = false, length = 30)
    private OutboxTaskType taskType;

    @Column(name = "aggregate_id", nullable = false)
    private Long aggregateId;

    // Outbox 스케줄러만 읽는 JSON이라 JSON 타입 매핑 없이 문자열로 둔다
    @Column(nullable = false, columnDefinition = "text")
    private String payload;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 12)
    private OutboxTaskStatus status;

    @Column(nullable = false)
    private int attempt;

    @Column(name = "next_attempt_at", nullable = false)
    private LocalDateTime nextAttemptAt;

    @Column(name = "locked_until")
    private LocalDateTime lockedUntil;

    @Column(name = "last_error", length = MAX_LAST_ERROR)
    private String lastError;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
