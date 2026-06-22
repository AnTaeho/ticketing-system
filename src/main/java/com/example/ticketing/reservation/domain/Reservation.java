package com.example.ticketing.reservation.domain;

import jakarta.persistence.Column;
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
public class Reservation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long concertId;
    private Long userId;

    // V6 멱등성 DB 최후 방어선 — 컨슈머 existsByTicketToken 체크가 1차 방어이지만,
    // 컨슈머 다중화/at-least-once 중복 전달 시 중복 INSERT를 DB가 차단한다.
    // V1~V5·WaitingRoom 예약은 ticketToken=null (MySQL은 NULL을 중복으로 보지 않으므로 다중 NULL 허용).
    @Column(unique = true)
    private String ticketToken;

    @Enumerated(EnumType.STRING)
    private ReservationStatus status;

    @CreatedDate
    private LocalDateTime createdAt;

    public static Reservation of(Long concertId, Long userId, ReservationStatus status) {
        Reservation reservation = new Reservation();
        reservation.concertId = concertId;
        reservation.userId = userId;
        reservation.status = status;
        return reservation;
    }

    public static Reservation ofV6(Long concertId, Long userId, ReservationStatus status, String ticketToken) {
        Reservation reservation = of(concertId, userId, status);
        reservation.ticketToken = ticketToken;
        return reservation;
    }

    public void updateStatus(ReservationStatus status) {
        this.status = status;
    }
}
