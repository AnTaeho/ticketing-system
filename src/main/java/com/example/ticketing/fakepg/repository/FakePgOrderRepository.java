package com.example.ticketing.fakepg.repository;

import com.example.ticketing.fakepg.domain.FakePgOrder;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

// 쓰기 뒤에 영속성 컨텍스트를 비워 같은 요청 안의 재조회가 DB 값을 읽게 한다
public interface FakePgOrderRepository extends JpaRepository<FakePgOrder, String> {

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            INSERT INTO fake_pg_transaction (order_id, amount, status, pg_tx_id, created_at, updated_at)
            VALUES (:orderId, :amount, 'PROCESSING', NULL, NOW(6), NOW(6))""", nativeQuery = true)
    int insertProcessing(@Param("orderId") String orderId, @Param("amount") int amount);

    // 승인 전에 도착한 취소가 남기는 묘비. 이후 승인 요청은 거절된다
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            INSERT INTO fake_pg_transaction (order_id, amount, status, pg_tx_id, created_at, updated_at)
            VALUES (:orderId, 0, 'CANCELLED', NULL, NOW(6), NOW(6))""", nativeQuery = true)
    int insertCancelled(@Param("orderId") String orderId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            UPDATE fake_pg_transaction SET status = 'APPROVED', pg_tx_id = :pgTxId, updated_at = NOW(6)
             WHERE order_id = :orderId AND status = 'PROCESSING'""", nativeQuery = true)
    int approveProcessing(@Param("orderId") String orderId, @Param("pgTxId") String pgTxId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            UPDATE fake_pg_transaction SET status = 'DECLINED', updated_at = NOW(6)
             WHERE order_id = :orderId AND status = 'PROCESSING'""", nativeQuery = true)
    int declineProcessing(@Param("orderId") String orderId);

    // 승인 지연 중에 앱이 죽어 남은 PROCESSING을 승인 시간 초과로 보고 거절로 끝낸다
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            UPDATE fake_pg_transaction SET status = 'DECLINED', updated_at = NOW(6)
             WHERE order_id = :orderId AND status = 'PROCESSING'
               AND created_at < DATE_SUB(NOW(6), INTERVAL :staleMicros MICROSECOND)""", nativeQuery = true)
    int declineStaleProcessing(@Param("orderId") String orderId, @Param("staleMicros") long staleMicros);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            UPDATE fake_pg_transaction SET status = 'CANCELLED', updated_at = NOW(6)
             WHERE order_id = :orderId AND status = 'APPROVED'""", nativeQuery = true)
    int cancelApproved(@Param("orderId") String orderId);
}
