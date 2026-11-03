package com.example.ticketing.booking.repository;

import com.example.ticketing.booking.domain.Booking;
import com.example.ticketing.booking.dto.BookingResult;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface BookingRepository extends JpaRepository<Booking, Long> {

    // 회차·공연·결제·알림은 다른 기능의 테이블이라 읽기만 한다. current_timestamp는 MySQL에서 NOW(6)과 같은 current_timestamp(6)이다
    String BOOKING_RESULT = """
            select new com.example.ticketing.booking.dto.BookingResult(
                   b.id, b.bookingNumber, b.status, b.createdAt, b.cancelledAt, b.totalAmount,
                   s.title, s.venueName, p.startsAt,
                   case when p.startsAt > current_timestamp then true else false end,
                   case when pay.status = CANCELLED then true else false end,
                   n.message)
              from Booking b
              join Performance p on p.id = b.performanceId
              join Show s on s.id = p.showId
              join Payment pay on pay.id = b.paymentId
              left join Notification n on n.bookingId = b.id
            """;

    Optional<Booking> findByHoldId(Long holdId);

    // 최신순
    @Query(BOOKING_RESULT + "where b.userId = :userId order by b.createdAt desc, b.id desc")
    List<BookingResult> findResults(@Param("userId") long userId);

    @Query(BOOKING_RESULT + "where b.userId = :userId and b.id = :bookingId")
    Optional<BookingResult> findResult(@Param("userId") long userId, @Param("bookingId") long bookingId);

    // 결제 기능의 테이블을 읽기만 한다
    @Query("select pay.orderId from Payment pay where pay.id = :paymentId")
    Optional<String> findPaymentOrderId(@Param("paymentId") long paymentId);

    @Query("select case when pay.status = CANCELLED then true else false end from Payment pay where pay.id = :paymentId")
    Optional<Boolean> findPaymentRefunded(@Param("paymentId") long paymentId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select b from Booking b where b.id = :id and b.userId = :userId")
    Optional<Booking> lockByIdAndUserId(@Param("id") long id, @Param("userId") long userId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            UPDATE booking SET status = 'CANCELLED', cancelled_at = NOW(6)
             WHERE booking_id = :id AND status = 'CONFIRMED'""", nativeQuery = true)
    int confirmedToCancelled(@Param("id") long id);
}
