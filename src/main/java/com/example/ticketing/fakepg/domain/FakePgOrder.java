package com.example.ticketing.fakepg.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CurrentTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.SourceType;
import org.hibernate.generator.EventType;
import org.hibernate.type.SqlTypes;
import org.springframework.data.domain.Persistable;

// 상태 전이는 승인 지연 중의 취소·시간 초과와 다투므로 FakePgOrderRepository의 조건부 UPDATE로만 한다
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "fake_pg_transaction")
public class FakePgOrder implements Persistable<String> {

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

    // 오래된 PROCESSING 판정이 DB 시각과 비교하므로 서버 시계가 아닌 DB 시각으로 넣는다
    @CurrentTimestamp(event = EventType.INSERT, source = SourceType.DB)
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @CurrentTimestamp(event = EventType.INSERT, source = SourceType.DB)
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Transient
    @Getter(AccessLevel.NONE)
    private boolean persisted;

    public static FakePgOrder processing(String orderId, int amount) {
        return create(orderId, amount, FakePgStatus.PROCESSING);
    }

    // 승인 전에 도착한 취소가 남기는 묘비. 이후 승인 요청은 거절된다
    public static FakePgOrder tombstone(String orderId) {
        return create(orderId, 0, FakePgStatus.CANCELLED);
    }

    private static FakePgOrder create(String orderId, int amount, FakePgStatus status) {
        FakePgOrder order = new FakePgOrder();
        order.orderId = orderId;
        order.amount = amount;
        order.status = status;
        return order;
    }

    @Override
    public String getId() {
        return orderId;
    }

    // 주문 번호를 받아 쓰므로 save가 조회 뒤 덮어쓰지(merge) 않고 넣게(persist) 한다. 같은 주문이 있으면 키 위반으로 끝난다
    @Override
    public boolean isNew() {
        return !persisted;
    }

    @PostLoad
    @PostPersist
    void markPersisted() {
        persisted = true;
    }
}
