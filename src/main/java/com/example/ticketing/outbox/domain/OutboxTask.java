package com.example.ticketing.outbox.domain;

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
import org.hibernate.annotations.CurrentTimestamp;
import org.hibernate.annotations.DynamicUpdate;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.SourceType;
import org.hibernate.generator.EventType;
import org.hibernate.type.SqlTypes;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

// 집기만 엔티티로 바꾸고, 결과 반영은 OutboxTaskRepository의 조건부 UPDATE로 한다. 집을 때 payload를 다시 쓰지 않게 바뀐 칸만 UPDATE한다
@Entity
@Getter
@DynamicUpdate
@EntityListeners(AuditingEntityListener.class)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "outbox_task",
        uniqueConstraints = @UniqueConstraint(name = "uk_outbox_dedup", columnNames = {"task_type", "aggregate_id"}),
        indexes = @Index(name = "idx_outbox_poll", columnList = "status, next_attempt_at"))
public class OutboxTask {

    public static final int MAX_LAST_ERROR = 500;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "outbox_task_id")
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

    // 집기가 DB 시각과 비교하므로 처음 값도 서버 시계가 아닌 DB 시각으로 넣는다
    @CurrentTimestamp(event = EventType.INSERT, source = SourceType.DB)
    @Column(name = "next_attempt_at", nullable = false)
    private LocalDateTime nextAttemptAt;

    @Column(name = "locked_until")
    private LocalDateTime lockedUntil;

    @Column(name = "last_error", length = MAX_LAST_ERROR)
    private String lastError;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    // 바로 집을 수 있는 작업으로 남는다
    public static OutboxTask pending(OutboxTaskType taskType, long aggregateId, String payload) {
        OutboxTask task = new OutboxTask();
        task.taskType = taskType;
        task.aggregateId = aggregateId;
        task.payload = payload;
        task.status = OutboxTaskStatus.PENDING;
        task.attempt = 0;
        return task;
    }

    // 기다리는 작업이나 잠금이 지나 멈춘 작업만 집는다. lockedUntil은 결과를 쓸 때 집은 그 작업인지 가리는 표지다
    public void claim(LocalDateTime lockedUntil) {
        if (status != OutboxTaskStatus.PENDING && status != OutboxTaskStatus.PROCESSING) {
            throw new IllegalStateException("집을 수 없는 Outbox 작업이다 id=" + id + " status=" + status);
        }
        this.status = OutboxTaskStatus.PROCESSING;
        this.lockedUntil = lockedUntil;
    }
}
