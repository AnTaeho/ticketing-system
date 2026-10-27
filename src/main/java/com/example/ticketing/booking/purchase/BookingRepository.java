package com.example.ticketing.booking.purchase;

import com.example.ticketing.booking.purchase.domain.Booking;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface BookingRepository extends JpaRepository<Booking, Long> {

    Optional<Booking> findByHoldId(Long holdId);

    @Query(value = "SELECT * FROM booking WHERE id = :id AND user_id = :userId FOR UPDATE", nativeQuery = true)
    Optional<Booking> lockByIdAndUserId(@Param("id") long id, @Param("userId") long userId);

    @Modifying
    @Query(value = """
            UPDATE booking SET status = 'CANCELLED', cancelled_at = NOW(6)
             WHERE id = :id AND status = 'CONFIRMED'""", nativeQuery = true)
    int markCancelled(@Param("id") long id);
}
