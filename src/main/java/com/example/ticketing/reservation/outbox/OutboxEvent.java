package com.example.ticketing.reservation.outbox;

import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

@Entity
@Getter
@EntityListeners(AuditingEntityListener.class)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OutboxEvent {

    private static final int MAX_RETRY = 5;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String ticketToken;
    private Long concertId;
    private String payload;

    @Enumerated(EnumType.STRING)
    private OutboxStatus status;

    private int retryCount;

    @CreatedDate
    private LocalDateTime createdAt;

    private LocalDateTime publishedAt;

    public static OutboxEvent create(Long concertId, String ticketToken, String payload) {
        OutboxEvent event = new OutboxEvent();
        event.concertId = concertId;
        event.ticketToken = ticketToken;
        event.payload = payload;
        event.status = OutboxStatus.PENDING;
        event.retryCount = 0;
        return event;
    }

    public void markPublished() {
        this.status = OutboxStatus.PUBLISHED;
        this.publishedAt = LocalDateTime.now();
    }

    public void incrementRetry() {
        this.retryCount++;
        if (this.retryCount >= MAX_RETRY) {
            this.status = OutboxStatus.FAILED;
        }
    }
}
