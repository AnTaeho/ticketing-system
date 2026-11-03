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

    // 조건부 UPDATE는 모두 flush·clear한다. 같은 트랜잭션에서 뒤이어 읽는 엔티티가 바뀌기 전 값으로 남지 않게

    Optional<Payment> findByUserIdAndIdempotencyKey(Long userId, String idempotencyKey);

    Optional<Payment> findByHoldId(Long holdId);

    Optional<Payment> findByIdAndUserId(Long id, Long userId);

    // 엔티티를 영속성 컨텍스트에 올리지 않고 hold를 먼저 잠그려고 ID만 읽는다
    @Query("select p.holdId from Payment p where p.id = :id")
    Optional<Long> findHoldIdById(@Param("id") long id);

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

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            UPDATE booking_payment
               SET status = 'APPROVED', pg_tx_id = :pgTxId, next_check_at = NULL, updated_at = NOW(6)
             WHERE payment_id = :id AND status IN ('REQUESTED', 'UNKNOWN')""", nativeQuery = true)
    int openToApproved(@Param("id") long id, @Param("pgTxId") String pgTxId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            UPDATE booking_payment
               SET status = 'DECLINED', next_check_at = NULL, updated_at = NOW(6)
             WHERE payment_id = :id AND status IN ('REQUESTED', 'UNKNOWN')""", nativeQuery = true)
    int openToDeclined(@Param("id") long id);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            UPDATE booking_payment
               SET status = 'CANCELLED', next_check_at = NULL, updated_at = NOW(6)
             WHERE payment_id = :id AND status IN ('REQUESTED', 'UNKNOWN')""", nativeQuery = true)
    int openToCancelled(@Param("id") long id);

    // 승인된 결제의 환불이 PG에서 확정됐을 때만 부른다
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            UPDATE booking_payment SET status = 'CANCELLED', updated_at = NOW(6)
             WHERE payment_id = :id AND status = 'APPROVED'""", nativeQuery = true)
    int approvedToCancelled(@Param("id") long id);

    // 대사가 다음 확인 시각을 정한다. 넘긴 오류가 없으면 이전 last_error를 둔다
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            UPDATE booking_payment
               SET next_check_at = DATE_ADD(NOW(6), INTERVAL :delayMicros MICROSECOND),
                   last_error = COALESCE(:lastError, last_error), updated_at = NOW(6)
             WHERE payment_id = :id AND status IN ('REQUESTED', 'UNKNOWN')""", nativeQuery = true)
    int reschedule(@Param("id") long id, @Param("delayMicros") long delayMicros,
                   @Param("lastError") String lastError);

    // 이미 확정된 결제는 건드리지 않는다
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            UPDATE booking_payment
               SET status = 'UNKNOWN', last_error = :lastError,
                   next_check_at = DATE_ADD(NOW(6), INTERVAL :recheckMicros MICROSECOND), updated_at = NOW(6)
             WHERE payment_id = :id AND status = 'REQUESTED'""", nativeQuery = true)
    int requestedToUnknown(@Param("id") long id, @Param("lastError") String lastError,
                           @Param("recheckMicros") long recheckMicros);
}
