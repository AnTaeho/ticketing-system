package com.example.ticketing.common.redis;

import java.time.Duration;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

// 여러 서버 중 한 곳만 돌아야 하는 스케줄러용 리스. 못 잡거나 Redis가 안 되면 그 주기는 건너뛴다
@Slf4j
@Component
public class SchedulerLease {

    private final StringRedisTemplate redis;
    private final String instanceId;

    public SchedulerLease(TicketingRedis ticketingRedis, InstanceId instanceId) {
        this.redis = ticketingRedis.template();
        this.instanceId = instanceId.value();
    }

    // 반환: 리스를 잡아 task를 실행했는지. task의 예외는 리스를 푼 뒤 그대로 던진다
    public boolean runIfLeader(String name, Duration ttl, Runnable task) {
        String key = RedisKeys.schedulerLease(name);
        Boolean acquired;
        try {
            acquired = RedisFailures.translate(() -> redis.opsForValue().setIfAbsent(key, instanceId, ttl));
        } catch (RedisUnavailableException e) {
            log.debug("[lease] 리스 획득 실패, 이번 주기는 건너뛴다 name={} cause={}", name, RedisFailures.describe(e));
            return false;
        }
        if (!Boolean.TRUE.equals(acquired)) {
            return false;
        }
        try {
            task.run();
        } finally {
            release(name, key);
        }
        return true;
    }

    // TTL이 지나 다른 인스턴스가 잡은 리스는 값이 달라 지우지 않는다. 못 지우면 TTL이 푼다
    private void release(String name, String key) {
        try {
            RedisFailures.translate(() -> redis.execute(RedisScripts.RELEASE, List.of(key), instanceId));
        } catch (RedisUnavailableException e) {
            log.debug("[lease] 리스 해제 실패, TTL로 풀린다 name={} cause={}", name, RedisFailures.describe(e));
        }
    }
}
