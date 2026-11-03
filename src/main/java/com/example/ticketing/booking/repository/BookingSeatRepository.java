package com.example.ticketing.booking.repository;

import com.example.ticketing.booking.domain.BookingSeat;
import com.example.ticketing.booking.domain.BookingSeatId;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface BookingSeatRepository extends JpaRepository<BookingSeat, BookingSeatId> {

    @Query("""
            select s from BookingSeat s
             where s.id.bookingId in :bookingIds
             order by s.id.bookingId, s.section, s.rowNo, s.seatNo""")
    List<BookingSeat> findByBookingIds(@Param("bookingIds") Collection<Long> bookingIds);
}
