package com.example.ticketing.booking.hold;

import com.example.ticketing.booking.hold.domain.SeatHold;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SeatHoldRepository extends JpaRepository<SeatHold, Long> {

    Optional<SeatHold> findByIdAndUserId(Long id, Long userId);

    @Query(value = """
            SELECT COUNT(*) FROM seat_hold
             WHERE user_id = :userId AND performance_id = :performanceId
               AND status IN ('HELD', 'PAYING')""", nativeQuery = true)
    long countActive(@Param("userId") long userId, @Param("performanceId") long performanceId);

    @Query(value = "SELECT * FROM seat_hold WHERE id = :id AND user_id = :userId FOR UPDATE", nativeQuery = true)
    Optional<SeatHold> lockByIdAndUserId(@Param("id") long id, @Param("userId") long userId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            UPDATE seat_hold SET status = 'RELEASED', updated_at = NOW(6)
             WHERE id = :id AND status = 'HELD'""", nativeQuery = true)
    int markReleased(@Param("id") long id);
}
