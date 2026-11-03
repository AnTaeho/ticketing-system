package com.example.ticketing.common.time;

import jakarta.persistence.EntityManager;
import jakarta.persistence.FlushModeType;
import java.time.LocalDateTime;
import org.springframework.stereotype.Component;

// 서버마다 시계가 달라도 만료 판정이 갈리지 않게 DB 시각을 기준으로 쓴다
@Component
public class DbClock {

    // local datetime은 MySQL에서 소수점 없는 localtimestamp가 되므로 current_timestamp(6)을 바꿔 받는다
    private static final String NOW = "select cast(current_timestamp as LocalDateTime)";

    private final EntityManager entityManager;

    public DbClock(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    // 시각만 묻는 쿼리라 트랜잭션 안에서 불러도 바뀐 엔티티를 미리 flush하지 않는다
    public LocalDateTime now() {
        return entityManager.createQuery(NOW, LocalDateTime.class)
                .setFlushMode(FlushModeType.COMMIT)
                .getSingleResult();
    }
}
