package com.example.ticketing.notification.repository;

import com.example.ticketing.notification.domain.IssuedSeat;
import com.example.ticketing.notification.domain.IssuedTicket;
import com.example.ticketing.notification.domain.Notification;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface NotificationRepository extends JpaRepository<Notification, Long> {

    boolean existsByBookingId(Long bookingId);

    // 예매·회차·공연·좌석 사본은 다른 기능의 테이블이라 읽기만 한다
    @Query("""
            select new com.example.ticketing.notification.domain.IssuedTicket(
                   b.bookingNumber, s.title, p.startsAt)
              from Booking b
              join Performance p on p.id = b.performanceId
              join Show s on s.id = p.showId
             where b.id = :bookingId""")
    Optional<IssuedTicket> findIssuedTicket(@Param("bookingId") long bookingId);

    @Query("""
            select new com.example.ticketing.notification.domain.IssuedSeat(s.section, s.rowNo, s.seatNo)
              from BookingSeat s
             where s.id.bookingId = :bookingId
             order by s.section, s.rowNo, s.seatNo""")
    List<IssuedSeat> findIssuedSeats(@Param("bookingId") long bookingId);
}
