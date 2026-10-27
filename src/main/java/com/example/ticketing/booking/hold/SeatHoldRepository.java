package com.example.ticketing.booking.hold;

import com.example.ticketing.booking.hold.domain.SeatHold;
import java.time.LocalDateTime;
import java.util.Collection;
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

    @Query(value = "SELECT * FROM seat_hold WHERE id = :id FOR UPDATE", nativeQuery = true)
    Optional<SeatHold> lockById(@Param("id") long id);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            UPDATE seat_hold SET status = 'RELEASED', updated_at = NOW(6)
             WHERE id = :id AND status = 'HELD'""", nativeQuery = true)
    int markReleased(@Param("id") long id);

    @Modifying
    @Query(value = """
            UPDATE seat_hold SET status = 'PAYING', pay_deadline = :payDeadline, updated_at = NOW(6)
             WHERE id = :id AND status = 'HELD' AND expires_at > NOW(6)""", nativeQuery = true)
    int markPaying(@Param("id") long id, @Param("payDeadline") LocalDateTime payDeadline);

    @Modifying
    @Query(value = """
            UPDATE seat_hold SET status = 'CONFIRMED', updated_at = NOW(6)
             WHERE id = :id AND status = 'PAYING'""", nativeQuery = true)
    int markConfirmed(@Param("id") long id);

    @Modifying
    @Query(value = """
            UPDATE seat_hold SET status = 'REFUNDING', updated_at = NOW(6)
             WHERE id = :id AND status = 'PAYING'""", nativeQuery = true)
    int markRefunding(@Param("id") long id);

    // PG 거절·망취소 확정 때만 부른다. 사용자 취소(HELD → RELEASED)는 markReleased
    @Modifying
    @Query(value = """
            UPDATE seat_hold SET status = 'RELEASED', updated_at = NOW(6)
             WHERE id = :id AND status = 'PAYING'""", nativeQuery = true)
    int markReleasedFromPaying(@Param("id") long id);

    // 결제 중(PAYING)은 대상이 아니다. 결제 대사가 결론을 낸다
    @Modifying
    @Query(value = """
            UPDATE seat_hold SET status = 'EXPIRED', updated_at = NOW(6)
             WHERE id IN (:ids) AND status = 'HELD'""", nativeQuery = true)
    int markExpired(@Param("ids") Collection<Long> ids);
}
