package com.example.ticketing.booking.repository;

import com.example.ticketing.booking.domain.Booking;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface BookingRepository extends JpaRepository<Booking, Long> {

    Optional<Booking> findByHoldId(long holdId);

    List<Booking> findByUserIdOrderByCreatedAtDescIdDesc(long userId);

    Optional<Booking> findByIdAndUserId(long id, long userId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select b from Booking b where b.id = :id and b.userId = :userId")
    Optional<Booking> lockByIdAndUserId(@Param("id") long id, @Param("userId") long userId);
}
