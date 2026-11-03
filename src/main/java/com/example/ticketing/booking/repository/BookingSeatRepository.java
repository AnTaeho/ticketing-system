package com.example.ticketing.booking.repository;

import com.example.ticketing.booking.domain.BookingSeat;
import com.example.ticketing.booking.domain.BookingSeatId;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface BookingSeatRepository extends JpaRepository<BookingSeat, BookingSeatId> {

    @Query("""
            select s from BookingSeat s
             where s.id.bookingId in :bookingIds
             order by s.id.bookingId, s.section, s.rowNo, s.seatNo""")
    List<BookingSeat> findByBookingIds(@Param("bookingIds") Collection<Long> bookingIds);

    // 확정 직후 이 hold의 SOLD 좌석을 그대로 옮겨 적는다
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            INSERT INTO booking_seat (booking_id, seat_id, section, row_no, seat_no, grade, price)
            SELECT :bookingId, seat_id, section, row_no, seat_no, grade, price
              FROM seat WHERE hold_id = :holdId AND status = 'SOLD'""", nativeQuery = true)
    int copySoldSeats(@Param("bookingId") long bookingId, @Param("holdId") long holdId);
}
