package com.example.ticketing.seat.domain;

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

// 상태 전이는 SeatRepository의 조건부 UPDATE로만 한다
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "seat",
        uniqueConstraints = @UniqueConstraint(name = "uk_seat_position",
                columnNames = {"performance_id", "section", "row_no", "seat_no"}),
        indexes = {
                @Index(name = "idx_seat_perf_status", columnList = "performance_id, status"),
                @Index(name = "idx_seat_hold", columnList = "hold_id")
        })
public class Seat {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "performance_id", nullable = false)
    private Long performanceId;

    @Column(nullable = false, length = 10)
    private String section;

    @Column(name = "row_no", nullable = false)
    private int rowNo;

    @Column(name = "seat_no", nullable = false)
    private int seatNo;

    // 설계 DDL대로 MySQL enum 타입 대신 varchar(10)으로 만든다
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 10)
    private SeatGrade grade;

    @Column(nullable = false)
    private int price;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 10)
    private SeatStatus status;

    @Column(name = "hold_id")
    private Long holdId;

    @Column(name = "held_until")
    private LocalDateTime heldUntil;
}
