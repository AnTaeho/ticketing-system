package com.example.ticketing.common.time;

import jakarta.persistence.EntityManager;
import jakarta.persistence.FlushModeType;
import java.time.LocalDateTime;
import org.springframework.stereotype.Component;

// 앱 서버와 DB의 시계가 다를 수 있어 만료 판정은 DB 시각 한곳에 모은다
@Component
public class DbClock {

    // local datetime은 MySQL에서 초 단위로 잘리므로 current_timestamp(6)을 LocalDateTime으로 바꿔 받는다
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
