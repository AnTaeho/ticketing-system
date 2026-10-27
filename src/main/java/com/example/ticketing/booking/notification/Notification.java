package com.example.ticketing.booking.notification;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
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
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

// 예매 하나에 발권 알림 하나. 워커가 같은 작업을 다시 처리해도 uk_notification_booking이 두 번째를 막는다
@Entity
@Getter
@EntityListeners(AuditingEntityListener.class)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "notification",
        uniqueConstraints = @UniqueConstraint(name = Notification.UK_BOOKING, columnNames = "booking_id"),
        indexes = @Index(name = "idx_notification_user", columnList = "user_id, created_at"))
public class Notification {

    public static final String UK_BOOKING = "uk_notification_booking";
    public static final int MAX_MESSAGE = 300;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "booking_id", nullable = false)
    private Long bookingId;

    @Column(nullable = false, length = MAX_MESSAGE)
    private String message;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    public static Notification ticketIssued(Long userId, Long bookingId, String message) {
        Notification notification = new Notification();
        notification.userId = userId;
        notification.bookingId = bookingId;
        notification.message = message;
        return notification;
    }
}
