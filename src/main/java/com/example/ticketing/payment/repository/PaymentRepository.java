package com.example.ticketing.payment.repository;

import com.example.ticketing.payment.domain.Payment;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PaymentRepository extends JpaRepository<Payment, Long> {

    // 조건부 UPDATE는 모두 flush·clear한다. 같은 트랜잭션에서 뒤이어 읽는 엔티티가 바뀌기 전 값으로 남지 않게

    Optional<Payment> findByUserIdAndIdempotencyKey(Long userId, String idempotencyKey);

    Optional<Payment> findByHoldId(Long holdId);

    Optional<Payment> findByIdAndUserId(Long id, Long userId);

    // 엔티티를 영속성 컨텍스트에 올리지 않고 hold를 먼저 잠그려고 ID만 읽는다
    @Query(value = "SELECT hold_id FROM booking_payment WHERE id = :id", nativeQuery = true)
    Optional<Long> findHoldIdById(@Param("id") long id);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            UPDATE booking_payment
               SET status = 'APPROVED', pg_tx_id = :pgTxId, next_check_at = NULL, updated_at = NOW(6)
             WHERE id = :id AND status IN ('REQUESTED', 'UNKNOWN')""", nativeQuery = true)
    int markApproved(@Param("id") long id, @Param("pgTxId") String pgTxId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            UPDATE booking_payment
               SET status = 'DECLINED', next_check_at = NULL, updated_at = NOW(6)
             WHERE id = :id AND status IN ('REQUESTED', 'UNKNOWN')""", nativeQuery = true)
    int markDeclined(@Param("id") long id);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            UPDATE booking_payment
               SET status = 'CANCELLED', next_check_at = NULL, updated_at = NOW(6)
             WHERE id = :id AND status IN ('REQUESTED', 'UNKNOWN')""", nativeQuery = true)
    int markCancelled(@Param("id") long id);

    // 승인된 결제의 환불이 PG에서 확정됐을 때만 부른다
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            UPDATE booking_payment SET status = 'CANCELLED', updated_at = NOW(6)
             WHERE id = :id AND status = 'APPROVED'""", nativeQuery = true)
    int markRefunded(@Param("id") long id);

    // 대사가 다음 확인 시각을 정한다. 넘긴 오류가 없으면 이전 last_error를 둔다
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            UPDATE booking_payment
               SET next_check_at = DATE_ADD(NOW(6), INTERVAL :delayMicros MICROSECOND),
                   last_error = COALESCE(:lastError, last_error), updated_at = NOW(6)
             WHERE id = :id AND status IN ('REQUESTED', 'UNKNOWN')""", nativeQuery = true)
    int reschedule(@Param("id") long id, @Param("delayMicros") long delayMicros,
                   @Param("lastError") String lastError);

    // 이미 확정된 결제는 건드리지 않는다
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            UPDATE booking_payment
               SET status = 'UNKNOWN', last_error = :lastError,
                   next_check_at = DATE_ADD(NOW(6), INTERVAL :recheckMicros MICROSECOND), updated_at = NOW(6)
             WHERE id = :id AND status = 'REQUESTED'""", nativeQuery = true)
    int markUnknown(@Param("id") long id, @Param("lastError") String lastError,
                    @Param("recheckMicros") long recheckMicros);
}
