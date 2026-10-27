package com.example.ticketing.booking.seat;

import com.example.ticketing.booking.seat.domain.Seat;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SeatRepository extends JpaRepository<Seat, Long> {

    List<Seat> findByPerformanceId(Long performanceId);

    long countByPerformanceId(Long performanceId);

    List<Seat> findByPerformanceIdAndHoldIdOrderByIdAsc(Long performanceId, Long holdId);

    // 빈자리이거나 선점 기한이 지난 좌석만 잡는다. 영향 행 수가 고른 좌석 수와 다르면 호출자가 롤백한다
    @Modifying
    @Query(value = """
            UPDATE seat
               SET status = 'HELD', hold_id = :holdId, held_until = :heldUntil
             WHERE performance_id = :performanceId
               AND id IN (:seatIds)
               AND (status = 'AVAILABLE' OR (status = 'HELD' AND held_until < NOW(6)))""", nativeQuery = true)
    int holdSeats(@Param("performanceId") long performanceId,
                  @Param("seatIds") Collection<Long> seatIds,
                  @Param("holdId") long holdId,
                  @Param("heldUntil") LocalDateTime heldUntil);

    @Query(value = "SELECT id FROM seat WHERE hold_id = :holdId AND status = 'HELD' ORDER BY id", nativeQuery = true)
    List<Long> findHeldSeatIds(@Param("holdId") long holdId);

    @Query(value = "SELECT id FROM seat WHERE hold_id = :holdId AND status = 'HELD' ORDER BY id FOR UPDATE",
            nativeQuery = true)
    List<Long> lockHeldSeatIds(@Param("holdId") long holdId);

    // 결제 중 좌석을 남이 가져가지 못하게 held_until을 결제 마감까지 늘린다
    @Modifying
    @Query(value = """
            UPDATE seat SET held_until = :heldUntil
             WHERE hold_id = :holdId AND status = 'HELD'""", nativeQuery = true)
    int extendHeldUntil(@Param("holdId") long holdId, @Param("heldUntil") LocalDateTime heldUntil);

    @Modifying
    @Query(value = """
            UPDATE seat SET status = 'SOLD', held_until = NULL
             WHERE hold_id = :holdId AND status = 'HELD'""", nativeQuery = true)
    int markSold(@Param("holdId") long holdId);

    // hold_id 조건 덕분에 남이 이미 가져간 좌석은 건드리지 않는다
    @Modifying
    @Query(value = """
            UPDATE seat SET status = 'AVAILABLE', hold_id = NULL, held_until = NULL
             WHERE hold_id = :holdId AND status = 'HELD'""", nativeQuery = true)
    int releaseHeldSeats(@Param("holdId") long holdId);

    @Modifying
    @Query(value = """
            UPDATE seat SET status = 'AVAILABLE', hold_id = NULL, held_until = NULL
             WHERE hold_id IN (:holdIds) AND status = 'HELD'""", nativeQuery = true)
    int releaseHeldSeatsOf(@Param("holdIds") Collection<Long> holdIds);

    @Query(value = "SELECT id FROM seat WHERE hold_id = :holdId AND status = 'SOLD' ORDER BY id FOR UPDATE",
            nativeQuery = true)
    List<Long> lockSoldSeatIds(@Param("holdId") long holdId);

    // 예매 취소 때만 부른다. hold_id를 비워 다른 사용자가 다시 잡을 수 있게 한다
    @Modifying
    @Query(value = """
            UPDATE seat SET status = 'AVAILABLE', hold_id = NULL, held_until = NULL
             WHERE hold_id = :holdId AND status = 'SOLD'""", nativeQuery = true)
    int releaseSoldSeats(@Param("holdId") long holdId);
}
