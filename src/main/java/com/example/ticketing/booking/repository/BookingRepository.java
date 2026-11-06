package com.example.ticketing.booking.repository;

import com.example.ticketing.booking.domain.Booking;
import com.example.ticketing.booking.dto.CancelTarget;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface BookingRepository extends JpaRepository<Booking, Long> {

    Optional<Booking> findByHoldId(Long holdId);

    // 최신순
    List<Booking> findByUserIdOrderByCreatedAtDescIdDesc(long userId);

    Optional<Booking> findByIdAndUserId(long id, long userId);

    // 회차·결제는 다른 기능의 테이블이라 읽기만 한다
    @Query("""
            select new com.example.ticketing.booking.dto.CancelTarget(b.id, b.status, b.paymentId,
                   case when p.startsAt > current_timestamp then true else false end,
                   case when pay.status = CANCELLED then true else false end)
              from Booking b
              join Performance p on p.id = b.performanceId
              join Payment pay on pay.id = b.paymentId
             where b.userId = :userId and b.id = :bookingId""")
    Optional<CancelTarget> findCancelTarget(@Param("userId") long userId, @Param("bookingId") long bookingId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select b from Booking b where b.id = :id and b.userId = :userId")
    Optional<Booking> lockByIdAndUserId(@Param("id") long id, @Param("userId") long userId);
}
