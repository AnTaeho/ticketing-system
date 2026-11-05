package com.example.ticketing.seat.repository;

import com.example.ticketing.seat.domain.HeldSeat;
import com.example.ticketing.seat.domain.PerformanceInfo;
import com.example.ticketing.seat.domain.Seat;
import com.example.ticketing.seat.domain.SeatInfo;
import com.example.ticketing.seat.domain.SeatMapSeat;
import jakarta.persistence.LockModeType;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SeatRepository extends JpaRepository<Seat, Long> {

    // 좌석을 잠그지 않고 다투는 전이는 조건부 UPDATE의 영향 행 수로 판정한다. 모두 flush·clear해 1차 캐시가 바뀌기 전 값으로 남지 않게 한다

    // 빈자리이거나 선점 기한이 지난(EXPIRED) 좌석만 잡는다. 영향 행 수가 고른 좌석 수와 다르면 호출자가 롤백한다
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update Seat s set s.status = HELD, s.holdId = :holdId, s.heldUntil = :heldUntil
             where s.performanceId = :performanceId
               and s.id in :seatIds
               and (s.status = AVAILABLE or (s.status = HELD and s.heldUntil < current_timestamp))""")
    int availableOrExpiredToHeld(@Param("performanceId") long performanceId,
                                 @Param("seatIds") Collection<Long> seatIds,
                                 @Param("holdId") long holdId,
                                 @Param("heldUntil") LocalDateTime heldUntil);

    // 회차는 show 기능의 테이블이라 읽기만 한다
    @Query("""
            select new com.example.ticketing.seat.domain.PerformanceInfo(p.id, p.openAt, p.startsAt)
              from Performance p
             where p.id = :performanceId""")
    Optional<PerformanceInfo> findPerformanceInfo(@Param("performanceId") long performanceId);

    @Query("""
            select new com.example.ticketing.seat.domain.SeatInfo(s.id, s.section, s.rowNo, s.seatNo, s.grade, s.price)
              from Seat s
             where s.performanceId = :performanceId
             order by s.id""")
    List<SeatInfo> findSeatInfos(@Param("performanceId") long performanceId);

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

    // 락 순서대로 id 오름차순으로 잠근다
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from Seat s where s.holdId = :holdId and s.status = HELD order by s.id")
    List<Seat> lockHeldSeats(@Param("holdId") long holdId);

    // 결제 중 좌석을 남이 가져가지 못하게 held_until을 결제 마감까지 늘린다
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update Seat s set s.heldUntil = :heldUntil where s.holdId = :holdId and s.status = HELD")
    int extendHeldUntil(@Param("holdId") long holdId, @Param("heldUntil") LocalDateTime heldUntil);

    // lockHeldSeats로 잠근 좌석을 한 문장으로 판다
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update Seat s set s.status = SOLD, s.heldUntil = null where s.holdId = :holdId and s.status = HELD")
    int heldToSold(@Param("holdId") long holdId);

    // hold_id 조건 덕분에 남이 이미 가져간 좌석은 건드리지 않는다
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update Seat s set s.status = AVAILABLE, s.holdId = null, s.heldUntil = null
             where s.holdId = :holdId and s.status = HELD""")
    int heldToAvailable(@Param("holdId") long holdId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update Seat s set s.status = AVAILABLE, s.holdId = null, s.heldUntil = null
             where s.holdId in :holdIds and s.status = HELD""")
    int heldToAvailableOfHolds(@Param("holdIds") Collection<Long> holdIds);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from Seat s where s.holdId = :holdId and s.status = SOLD order by s.id")
    List<Seat> lockSoldSeats(@Param("holdId") long holdId);
}
