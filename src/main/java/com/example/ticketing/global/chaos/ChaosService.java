package com.example.ticketing.global.chaos;

import com.zaxxer.hikari.HikariDataSource;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;

@Slf4j
@Service
@RequiredArgsConstructor
public class ChaosService {

    private final DataSource dataSource;
    private final KafkaListenerEndpointRegistry kafkaListenerEndpointRegistry;
    private final ChaosState chaosState;

    public void constrainHikari(int maxPoolSize) {
        HikariDataSource hikari = (HikariDataSource) dataSource;
        chaosState.setOriginalMaxPoolSize(hikari.getMaximumPoolSize());
        chaosState.setHikariMaxPoolSize(maxPoolSize);
        chaosState.setHikariConstrained(true);
        hikari.setMaximumPoolSize(maxPoolSize);
        log.info("[Chaos] HikariCP 풀 제한 - {}→{}", chaosState.getOriginalMaxPoolSize(), maxPoolSize);
    }

    public void restoreHikari() {
        if (!chaosState.isHikariConstrained()) return;
        HikariDataSource hikari = (HikariDataSource) dataSource;
        hikari.setMaximumPoolSize(chaosState.getOriginalMaxPoolSize());
        log.info("[Chaos] HikariCP 복구 - maxPoolSize={}", chaosState.getOriginalMaxPoolSize());
        chaosState.resetHikari();
    }

    public void setRedisDelay(long ms) {
        chaosState.setRedisDelayMs(ms);
        chaosState.setRedisMode(ChaosState.RedisMode.DELAY);
        log.info("[Chaos] Redis 지연 설정 - {}ms", ms);
    }

    public void setRedisBlock() {
        chaosState.setRedisMode(ChaosState.RedisMode.BLOCK);
        log.info("[Chaos] Redis 차단 설정");
    }

    public void restoreRedis() {
        chaosState.setRedisMode(ChaosState.RedisMode.NONE);
        chaosState.setRedisDelayMs(0);
        log.info("[Chaos] Redis 복구");
    }

    public void pauseKafka() {
        kafkaListenerEndpointRegistry.getAllListenerContainers()
                .forEach(MessageListenerContainer::pause);
        chaosState.setKafkaPaused(true);
        log.info("[Chaos] Kafka 컨슈머 일시 중지");
    }

    public void resumeKafka() {
        kafkaListenerEndpointRegistry.getAllListenerContainers()
                .forEach(MessageListenerContainer::resume);
        chaosState.setKafkaPaused(false);
        log.info("[Chaos] Kafka 컨슈머 재개");
    }

    public void resetAll() {
        restoreHikari();
        restoreRedis();
        resumeKafka();
        log.info("[Chaos] 전체 초기화 완료");
    }
}
