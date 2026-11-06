package com.example.ticketing.payment.repository;

import com.example.ticketing.payment.domain.Payment;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PaymentRepository extends JpaRepository<Payment, Long> {

    // bulk UPDATE는 모두 flush·clear해 뒤이어 읽는 엔티티에 바뀌기 전 값이 남지 않게 한다

    Optional<Payment> findByUserIdAndIdempotencyKey(long userId, String idempotencyKey);

    Optional<Payment> findByHoldId(long holdId);

    Optional<Payment> findByIdAndUserId(long id, long userId);

    // hold를 먼저 잠그려고 ID만 읽는다. 결제 엔티티를 먼저 올리면 락 뒤에도 옛 값을 볼 수 있다
    @Query("select p.holdId from Payment p where p.id = :id")
    Optional<Long> findHoldIdById(@Param("id") long id);

    // hold를 잠근 뒤에만 부른다. 락 순서는 seat_hold → booking_payment → seat이다
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Payment p where p.id = :id")
    Optional<Payment> lockById(@Param("id") long id);

    @Query("""
            select p from Payment p
             where p.status in (REQUESTED, UNKNOWN) and p.nextCheckAt <= current_timestamp
             order by p.id""")
    List<Payment> findDue(Limit limit);

    // 대사가 다음 확인 시각을 정한다. 결제를 잠그지 않아 확정과 겹칠 수 있으므로 열린 결제만 바꾼다
    // lastError가 null이면 이전 값을 둔다. HQL은 Duration을 시각에 더하지 못해 나노초로 넘긴다
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update Payment p
               set p.nextCheckAt = current_timestamp + (:delayNanos) nanosecond,
                   p.lastError = coalesce(:lastError, p.lastError), p.updatedAt = current_timestamp
             where p.id = :id and p.status in (REQUESTED, UNKNOWN)""")
    int reschedule(@Param("id") long id, @Param("delayNanos") long delayNanos,
                   @Param("lastError") String lastError);

    // hold를 잠그지 않아 대사의 결론과 겹칠 수 있다. REQUESTED일 때만 바꿔 먼저 난 결론을 덮지 않는다
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update Payment p
               set p.status = UNKNOWN, p.lastError = :lastError,
                   p.nextCheckAt = current_timestamp + (:recheckNanos) nanosecond, p.updatedAt = current_timestamp
             where p.id = :id and p.status = REQUESTED""")
    int requestedToUnknown(@Param("id") long id, @Param("lastError") String lastError,
                           @Param("recheckNanos") long recheckNanos);
}
