package com.example.ticketing.hold.repository;

import com.example.ticketing.hold.domain.ClaimedHold;
import com.example.ticketing.hold.domain.SeatHold;
import com.example.ticketing.hold.dto.ActiveHoldResult;
import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

public interface SeatHoldRepository extends JpaRepository<SeatHold, Long> {

    // bulk UPDATE는 모두 flush·clear한다. 같은 트랜잭션에서 뒤이어 읽는 엔티티가 바뀌기 전 값으로 남지 않게

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

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select h from SeatHold h where h.id = :id and h.userId = :userId")
    Optional<SeatHold> lockByIdAndUserId(@Param("id") long id, @Param("userId") long userId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select h from SeatHold h where h.id = :id")
    Optional<SeatHold> lockById(@Param("id") long id);

    // 잠금 시간 -2는 Hibernate에서 FOR UPDATE SKIP LOCKED다. 트랜잭션 밖에서는 락이 바로 풀려 의미가 없으므로 상태 변경과 같은 트랜잭션에서만 부른다
    @Transactional(propagation = Propagation.MANDATORY)
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2"))
    @Query("""
            select new com.example.ticketing.hold.domain.ClaimedHold(h.id, h.performanceId, h.holdToken)
              from SeatHold h
             where h.status = HELD and h.expiresAt < current_timestamp
             order by h.id""")
    List<ClaimedHold> claimExpired(Limit limit);

    // claimExpired로 잠근 hold를 한 문장으로 만료한다. 결제 중(PAYING)은 대상이 아니다. 결제 대사가 결론을 낸다
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update SeatHold h set h.status = EXPIRED, h.updatedAt = current_timestamp
             where h.id in :ids and h.status = HELD""")
    int heldToExpired(@Param("ids") Collection<Long> ids);
}
