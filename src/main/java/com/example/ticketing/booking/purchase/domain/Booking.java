package com.example.ticketing.booking.purchase.domain;

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
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

// uk_booking_hold가 이중 확정의 DB 최종선이다
@Entity
@Getter
@EntityListeners(AuditingEntityListener.class)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "booking",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_booking_hold", columnNames = "hold_id"),
                @UniqueConstraint(name = "uk_booking_number", columnNames = "booking_number")
        },
        indexes = @Index(name = "idx_booking_user", columnList = "user_id, created_at"))
public class Booking {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "booking_number", nullable = false, length = 20)
    private String bookingNumber;

    @Column(name = "hold_id", nullable = false)
    private Long holdId;

    @Column(name = "payment_id", nullable = false)
    private Long paymentId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "performance_id", nullable = false)
    private Long performanceId;

    @Column(name = "total_amount", nullable = false)
    private int totalAmount;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 12)
    private BookingStatus status;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "cancelled_at")
    private LocalDateTime cancelledAt;

    public static Booking confirmed(String bookingNumber, Long holdId, Long paymentId, Long userId,
                                    Long performanceId, int totalAmount) {
        Booking booking = new Booking();
        booking.bookingNumber = bookingNumber;
        booking.holdId = holdId;
        booking.paymentId = paymentId;
        booking.userId = userId;
        booking.performanceId = performanceId;
        booking.totalAmount = totalAmount;
        booking.status = BookingStatus.CONFIRMED;
        return booking;
    }
}
