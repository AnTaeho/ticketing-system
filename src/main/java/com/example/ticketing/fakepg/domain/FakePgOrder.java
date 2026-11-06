package com.example.ticketing.fakepg.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

// 읽기 전용이다. 넣기와 상태 전이는 승인 지연 중의 취소·시간 초과와 다투므로 FakePgOrderRepository의 INSERT·조건부 UPDATE로만 한다
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "fake_pg_transaction")
public class FakePgOrder {

    @Id
    @Column(name = "order_id", length = 40)
    private String orderId;

    @Column(nullable = false)
    private int amount;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 12)
    private FakePgStatus status;

    @Column(name = "pg_tx_id", length = 64)
    private String pgTxId;

    // 오래된 PROCESSING 판정이 DB 시각과 비교하므로 DB 시각으로 넣는다
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
