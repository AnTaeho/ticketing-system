package com.example.ticketing.seat.repository;

import com.example.ticketing.seat.domain.HeldSeat;
import com.example.ticketing.seat.domain.PerformanceInfo;
import com.example.ticketing.seat.domain.Seat;
import com.example.ticketing.seat.domain.SeatInfo;
import com.example.ticketing.seat.domain.SeatMapSeat;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SeatRepository extends JpaRepository<Seat, Long> {

    // 조건부 UPDATE는 모두 flush·clear한다. 같은 트랜잭션에서 뒤이어 읽는 엔티티가 바뀌기 전 값으로 남지 않게

    // 빈자리이거나 선점 기한이 지난(EXPIRED) 좌석만 잡는다. 영향 행 수가 고른 좌석 수와 다르면 호출자가 롤백한다
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            UPDATE seat
               SET status = 'HELD', hold_id = :holdId, held_until = :heldUntil
             WHERE performance_id = :performanceId
               AND id IN (:seatIds)
               AND (status = 'AVAILABLE' OR (status = 'HELD' AND held_until < NOW(6)))""", nativeQuery = true)
    int availableOrExpiredToHeld(@Param("performanceId") long performanceId,
                                 @Param("seatIds") Collection<Long> seatIds,
                                 @Param("holdId") long holdId,
                                 @Param("heldUntil") LocalDateTime heldUntil);

    // 회차는 show 기능의 테이블이라 읽기만 한다
    @Query("""
            select new com.example.ticketing.seat.domain.PerformanceInfo(p.id, p.openAt, p.startsAt, p.gateCloseAt)
              from Performance p
             where p.id = :performanceId""")
    Optional<PerformanceInfo> findPerformanceInfo(@Param("performanceId") long performanceId);

    @Query("""
            select new com.example.ticketing.seat.domain.SeatInfo(s.id, s.section, s.rowNo, s.seatNo, s.grade, s.price)
              from Seat s
             where s.performanceId = :performanceId
             order by s.id""")
    List<SeatInfo> findSeatInfos(@Param("performanceId") long performanceId);

    // 예매 중인 회차: open_at <= DB 시각 < starts_at
    @Query("""
            select p.id from Performance p
             where p.openAt <= current_timestamp and p.startsAt > current_timestamp
             order by p.id""")
    List<Long> findOpenPerformanceIds();

    // 구역 → 열 → 번호 순이다
    @Query("""
            select new com.example.ticketing.seat.domain.SeatMapSeat(
                   s.id, s.section, s.rowNo, s.seatNo, s.grade, s.price, s.status)
              from Seat s
             where s.performanceId = :performanceId
             order by s.section, s.rowNo, s.seatNo""")
    List<SeatMapSeat> findSeatMap(@Param("performanceId") long performanceId);

    // 지금 이 hold를 가리키는 좌석(HELD·SOLD) ID 오름차순
    @Query("select s.id from Seat s where s.performanceId = :performanceId and s.holdId = :holdId order by s.id")
    List<Long> findSeatIdsOfHold(@Param("performanceId") long performanceId, @Param("holdId") long holdId);

    @Query("select s.id from Seat s where s.holdId = :holdId and s.status = HELD order by s.id")
    List<Long> findHeldSeatIds(@Param("holdId") long holdId);

    @Query("select new com.example.ticketing.seat.domain.HeldSeat(s.holdId, s.id) from Seat s "
            + "where s.holdId in :holdIds and s.status = HELD order by s.id")
    List<HeldSeat> findHeldSeats(@Param("holdIds") Collection<Long> holdIds);

    @Query(value = "SELECT id FROM seat WHERE hold_id = :holdId AND status = 'HELD' ORDER BY id FOR UPDATE",
            nativeQuery = true)
    List<Long> lockHeldSeatIds(@Param("holdId") long holdId);

    // 결제 중 좌석을 남이 가져가지 못하게 held_until을 결제 마감까지 늘린다
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            UPDATE seat SET held_until = :heldUntil
             WHERE hold_id = :holdId AND status = 'HELD'""", nativeQuery = true)
    int extendHeldUntil(@Param("holdId") long holdId, @Param("heldUntil") LocalDateTime heldUntil);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            UPDATE seat SET status = 'SOLD', held_until = NULL
             WHERE hold_id = :holdId AND status = 'HELD'""", nativeQuery = true)
    int heldToSold(@Param("holdId") long holdId);

    // hold_id 조건 덕분에 남이 이미 가져간 좌석은 건드리지 않는다
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            UPDATE seat SET status = 'AVAILABLE', hold_id = NULL, held_until = NULL
             WHERE hold_id = :holdId AND status = 'HELD'""", nativeQuery = true)
    int heldToAvailable(@Param("holdId") long holdId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            UPDATE seat SET status = 'AVAILABLE', hold_id = NULL, held_until = NULL
             WHERE hold_id IN (:holdIds) AND status = 'HELD'""", nativeQuery = true)
    int heldToAvailableOfHolds(@Param("holdIds") Collection<Long> holdIds);

    @Query(value = "SELECT id FROM seat WHERE hold_id = :holdId AND status = 'SOLD' ORDER BY id FOR UPDATE",
            nativeQuery = true)
    List<Long> lockSoldSeatIds(@Param("holdId") long holdId);

    // 예매 취소 때만 부른다. hold_id를 비워 다른 사용자가 다시 잡을 수 있게 한다
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            UPDATE seat SET status = 'AVAILABLE', hold_id = NULL, held_until = NULL
             WHERE hold_id = :holdId AND status = 'SOLD'""", nativeQuery = true)
    int soldToAvailable(@Param("holdId") long holdId);
}
