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

// 상태 전이 메서드는 이 좌석 행을 비관적 락으로 잠근 트랜잭션에서만 부른다. 잡기·풀기와 결제 확정의 좌석 여러 개는 SeatRepository의 UPDATE 한 문장이다
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
    @Column(name = "seat_id")
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

    public SeatInfo info() {
        return new SeatInfo(id, section, rowNo, seatNo, grade, price);
    }

    // 예매 취소 때만 부른다. hold_id를 비워 다른 사용자가 다시 잡을 수 있게 한다
    public void soldToAvailable() {
        moveTo(SeatStatus.SOLD, SeatStatus.AVAILABLE);
        holdId = null;
        heldUntil = null;
    }

    private void moveTo(SeatStatus from, SeatStatus to) {
        if (status != from) {
            throw new IllegalStateException("좌석 " + id + "는 " + status + "라 " + from + " → " + to + "로 바꿀 수 없다");
        }
        status = to;
    }
}
