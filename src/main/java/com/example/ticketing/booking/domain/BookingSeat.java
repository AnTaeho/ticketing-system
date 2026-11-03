package com.example.ticketing.booking.domain;

import com.example.ticketing.seat.domain.SeatGrade;
import com.example.ticketing.seat.domain.SeatInfo;
import jakarta.persistence.Column;
import jakarta.persistence.ConstraintMode;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.MapsId;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

// 예매 취소 뒤 좌석의 hold_id가 비므로 확정 시점의 좌석 정보를 사본으로 남긴다. Booking과 함께 저장된다
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "booking_seat")
public class BookingSeat {

    @EmbeddedId
    private BookingSeatId id;

    // 다른 테이블처럼 DB 외래 키는 두지 않는다
    @MapsId("bookingId")
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "booking_id", foreignKey = @ForeignKey(ConstraintMode.NO_CONSTRAINT))
    @Getter(AccessLevel.NONE)
    private Booking booking;

    @Column(nullable = false, length = 10)
    private String section;

    @Column(name = "row_no", nullable = false)
    private int rowNo;

    @Column(name = "seat_no", nullable = false)
    private int seatNo;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 10)
    private SeatGrade grade;

    @Column(nullable = false)
    private int price;

    static BookingSeat copyOf(Booking booking, SeatInfo seat) {
        BookingSeat copy = new BookingSeat();
        copy.id = new BookingSeatId(null, seat.id());
        copy.booking = booking;
        copy.section = seat.section();
        copy.rowNo = seat.rowNo();
        copy.seatNo = seat.seatNo();
        copy.grade = seat.grade();
        copy.price = seat.price();
        return copy;
    }
}
