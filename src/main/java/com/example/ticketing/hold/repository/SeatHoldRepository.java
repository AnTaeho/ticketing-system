package com.example.ticketing.hold.repository;

import com.example.ticketing.hold.domain.LiveGateSeat;
import com.example.ticketing.hold.domain.SeatHold;
import com.example.ticketing.hold.dto.ActiveHoldResult;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SeatHoldRepository extends JpaRepository<SeatHold, Long> {

    // 조건부 UPDATE는 모두 flush·clear한다. 같은 트랜잭션에서 뒤이어 읽는 엔티티가 바뀌기 전 값으로 남지 않게

    Optional<SeatHold> findByIdAndUserId(Long id, Long userId);

    @Query("""
            select count(h) from SeatHold h
             where h.userId = :userId and h.performanceId = :performanceId and h.status in (HELD, PAYING)""")
    long countActive(@Param("userId") long userId, @Param("performanceId") long performanceId);

    @Query("""
            select new com.example.ticketing.hold.dto.ActiveHoldResult(h.id, h.status, h.expiresAt)
              from SeatHold h
             where h.userId = :userId and h.performanceId = :performanceId and h.status in (HELD, PAYING)
             order by h.id desc
             limit 1""")
    Optional<ActiveHoldResult> findActive(@Param("userId") long userId, @Param("performanceId") long performanceId);

    // 결제 테이블은 읽기만 한다. 결제 기능이 hold 기능을 부르므로 결제 서비스를 부르면 순환이 된다
    @Query("select p.id from Payment p where p.holdId = :holdId")
    Optional<Long> findPaymentIdByHoldId(@Param("holdId") long holdId);

    // 좌석 행이 이 토큰의 hold를 가리키는 HELD·SOLD 좌석
    @Query("""
            select new com.example.ticketing.hold.domain.LiveGateSeat(h.holdToken, s.id)
              from SeatHold h
              join Seat s on s.holdId = h.id
             where h.performanceId = :performanceId and h.holdToken in :tokens
               and s.performanceId = :performanceId and s.status in (HELD, SOLD)""")
    List<LiveGateSeat> findLiveGateSeats(@Param("performanceId") long performanceId,
                                         @Param("tokens") Collection<String> tokens);

    // HELD·PAYING hold의 토큰만 userhold 키가 살아 있다
    @Query("""
            select h.holdToken from SeatHold h
             where h.performanceId = :performanceId and h.holdToken in :tokens and h.status in (HELD, PAYING)""")
    List<String> findLiveUserHoldTokens(@Param("performanceId") long performanceId,
                                        @Param("tokens") Collection<String> tokens);

    @Query(value = "SELECT * FROM seat_hold WHERE id = :id AND user_id = :userId FOR UPDATE", nativeQuery = true)
    Optional<SeatHold> lockByIdAndUserId(@Param("id") long id, @Param("userId") long userId);

    @Query(value = "SELECT * FROM seat_hold WHERE id = :id FOR UPDATE", nativeQuery = true)
    Optional<SeatHold> lockById(@Param("id") long id);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            UPDATE seat_hold SET status = 'RELEASED', updated_at = NOW(6)
             WHERE id = :id AND status = 'HELD'""", nativeQuery = true)
    int heldToReleased(@Param("id") long id);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            UPDATE seat_hold SET status = 'PAYING', pay_deadline = :payDeadline, updated_at = NOW(6)
             WHERE id = :id AND status = 'HELD' AND expires_at > NOW(6)""", nativeQuery = true)
    int heldToPaying(@Param("id") long id, @Param("payDeadline") LocalDateTime payDeadline);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            UPDATE seat_hold SET status = 'CONFIRMED', updated_at = NOW(6)
             WHERE id = :id AND status = 'PAYING'""", nativeQuery = true)
    int payingToConfirmed(@Param("id") long id);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            UPDATE seat_hold SET status = 'REFUNDING', updated_at = NOW(6)
             WHERE id = :id AND status = 'PAYING'""", nativeQuery = true)
    int payingToRefunding(@Param("id") long id);

    // PG 거절·망취소 확정 때만 부른다. 사용자 취소(HELD → RELEASED)는 heldToReleased
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            UPDATE seat_hold SET status = 'RELEASED', updated_at = NOW(6)
             WHERE id = :id AND status = 'PAYING'""", nativeQuery = true)
    int payingToReleased(@Param("id") long id);

    // 좌석 상실로 환불하던 hold를 환불 확정 뒤 닫는다. 예매 취소의 환불은 hold가 CONFIRMED라 대상이 아니다
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            UPDATE seat_hold SET status = 'RELEASED', updated_at = NOW(6)
             WHERE id = :id AND status = 'REFUNDING'""", nativeQuery = true)
    int refundingToReleased(@Param("id") long id);

    // 결제 중(PAYING)은 대상이 아니다. 결제 대사가 결론을 낸다
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            UPDATE seat_hold SET status = 'EXPIRED', updated_at = NOW(6)
             WHERE id IN (:ids) AND status = 'HELD'""", nativeQuery = true)
    int heldToExpired(@Param("ids") Collection<Long> ids);
}
