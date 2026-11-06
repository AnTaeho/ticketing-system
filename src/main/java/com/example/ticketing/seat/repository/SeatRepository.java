package com.example.ticketing.seat.repository;

import com.example.ticketing.seat.domain.HeldSeat;
import com.example.ticketing.seat.domain.Seat;
import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SeatRepository extends JpaRepository<Seat, Long> {

    // 좌석을 잠그지 않고 다투는 전이는 조건부 UPDATE의 영향 행 수로 판정한다. 모두 flush·clear해 1차 캐시가 바뀌기 전 값으로 남지 않게 한다

    // 빈자리만 잡는다. 영향 행 수가 고른 좌석 수와 다르면 호출자가 롤백한다
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update Seat s set s.status = HELD, s.holdId = :holdId
             where s.performanceId = :performanceId
               and s.id in :seatIds
               and s.status = AVAILABLE""")
    int availableToHeld(@Param("performanceId") long performanceId,
                        @Param("seatIds") Collection<Long> seatIds,
                        @Param("holdId") long holdId);

    List<Seat> findByPerformanceId(long performanceId, Sort sort);

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

    // lockHeldSeats로 잠근 좌석을 한 문장으로 판다
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update Seat s set s.status = SOLD where s.holdId = :holdId and s.status = HELD")
    int heldToSold(@Param("holdId") long holdId);

    // 이 hold가 쥔 HELD 좌석만 푼다
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update Seat s set s.status = AVAILABLE, s.holdId = null
             where s.holdId = :holdId and s.status = HELD""")
    int heldToAvailable(@Param("holdId") long holdId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update Seat s set s.status = AVAILABLE, s.holdId = null
             where s.holdId in :holdIds and s.status = HELD""")
    int heldToAvailableOfHolds(@Param("holdIds") Collection<Long> holdIds);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from Seat s where s.holdId = :holdId and s.status = SOLD order by s.id")
    List<Seat> lockSoldSeats(@Param("holdId") long holdId);
}
