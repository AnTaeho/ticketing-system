package com.example.ticketing.payment.repository;

import com.example.ticketing.payment.domain.Payment;
import com.example.ticketing.payment.dto.ClaimedPayment;
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

public interface PaymentRepository extends JpaRepository<Payment, Long> {

    // bulk UPDATE는 모두 flush·clear한다. 같은 트랜잭션에서 뒤이어 읽는 엔티티가 바뀌기 전 값으로 남지 않게

    Optional<Payment> findByUserIdAndIdempotencyKey(Long userId, String idempotencyKey);

    Optional<Payment> findByHoldId(Long holdId);

    Optional<Payment> findByIdAndUserId(Long id, Long userId);

    // 엔티티를 영속성 컨텍스트에 올리지 않고 hold를 먼저 잠그려고 ID만 읽는다
    @Query("select p.holdId from Payment p where p.id = :id")
    Optional<Long> findHoldIdById(@Param("id") long id);

    // hold를 잠근 뒤에만 부른다. 락 순서는 seat_hold → booking_payment → seat이다
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Payment p where p.id = :id")
    Optional<Payment> lockById(@Param("id") long id);

    // 잠금 시간 -2는 Hibernate에서 FOR UPDATE SKIP LOCKED다. 트랜잭션 밖에서는 락이 바로 풀려 서버끼리 같은 행을 집으므로 미루기와 같은 트랜잭션에서만 부른다
    // HQL은 Duration 파라미터를 시각에 더하지 못해 기간을 나노초로 넘기고 nanosecond 단위로 더한다
    @Transactional(propagation = Propagation.MANDATORY)
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2"))
    @Query("""
            select new com.example.ticketing.payment.dto.ClaimedPayment(p.id, p.orderId,
                   case when p.createdAt <= current_timestamp - (:notFoundCancelAfterNanos) nanosecond then true else false end)
              from Payment p
             where p.status in (REQUESTED, UNKNOWN) and p.nextCheckAt <= current_timestamp
             order by p.id""")
    List<ClaimedPayment> claimDue(@Param("notFoundCancelAfterNanos") long notFoundCancelAfterNanos, Limit limit);

    @Transactional(propagation = Propagation.MANDATORY)
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update Payment p set p.nextCheckAt = current_timestamp + (:leaseNanos) nanosecond, p.updatedAt = current_timestamp
             where p.id in :ids""")
    int lease(@Param("ids") Collection<Long> ids, @Param("leaseNanos") long leaseNanos);

    // 대사가 다음 확인 시각을 정한다. 결제를 잠그지 않아 확정과 겹칠 수 있어 열린 결제만 바꾼다. 넘긴 오류가 없으면 이전 last_error를 둔다
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update Payment p
               set p.nextCheckAt = current_timestamp + (:delayNanos) nanosecond,
                   p.lastError = coalesce(:lastError, p.lastError), p.updatedAt = current_timestamp
             where p.id = :id and p.status in (REQUESTED, UNKNOWN)""")
    int reschedule(@Param("id") long id, @Param("delayNanos") long delayNanos,
                   @Param("lastError") String lastError);

    // hold를 잠그지 않아 대사의 확정과 겹칠 수 있다. 이미 확정된 결제는 건드리지 않는다
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update Payment p
               set p.status = UNKNOWN, p.lastError = :lastError,
                   p.nextCheckAt = current_timestamp + (:recheckNanos) nanosecond, p.updatedAt = current_timestamp
             where p.id = :id and p.status = REQUESTED""")
    int requestedToUnknown(@Param("id") long id, @Param("lastError") String lastError,
                           @Param("recheckNanos") long recheckNanos);
}
