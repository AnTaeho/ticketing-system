package com.example.ticketing.fakepg.repository;

import com.example.ticketing.fakepg.domain.FakePgOrder;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

// 승인·취소·시간 초과가 같은 행을 두고 다투므로 잠그지 않고 "지금 상태가 X일 때만" 바꿔 영향 행 수로 판정한다
public interface FakePgOrderRepository extends JpaRepository<FakePgOrder, String> {

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update FakePgOrder o set o.status = APPROVED, o.pgTxId = :pgTxId, o.updatedAt = current_timestamp
             where o.orderId = :orderId and o.status = PROCESSING""")
    int processingToApproved(@Param("orderId") String orderId, @Param("pgTxId") String pgTxId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update FakePgOrder o set o.status = DECLINED, o.updatedAt = current_timestamp
             where o.orderId = :orderId and o.status = PROCESSING""")
    int processingToDeclined(@Param("orderId") String orderId);

    // 승인 지연 중에 앱이 죽어 남은 PROCESSING을 승인 시간 초과로 보고 거절로 끝낸다
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update FakePgOrder o set o.status = DECLINED, o.updatedAt = current_timestamp
             where o.orderId = :orderId and o.status = PROCESSING
               and o.createdAt < current_timestamp - :staleNanos nanosecond""")
    int staleProcessingToDeclined(@Param("orderId") String orderId, @Param("staleNanos") long staleNanos);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update FakePgOrder o set o.status = CANCELLED, o.updatedAt = current_timestamp
             where o.orderId = :orderId and o.status = APPROVED""")
    int approvedToCancelled(@Param("orderId") String orderId);
}
