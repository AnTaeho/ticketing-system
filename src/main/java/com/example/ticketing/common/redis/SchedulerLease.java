package com.example.ticketing.common.redis;

import io.lettuce.core.RedisException;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

// 여러 서버 중 한 곳만 돌아야 하는 스케줄러용 리스. 못 잡거나 Redis가 안 되면 그 주기는 건너뛴다
@Slf4j
@Component
public class SchedulerLease {

    private static final String KEY_PREFIX = "sched:lease:";
    private static final DefaultRedisScript<Long> RELEASE = releaseScript();

    private final StringRedisTemplate redis;
    private final String instanceId;

    @Autowired
    public SchedulerLease(TicketingRedis bookingRedis) {
        this(bookingRedis, UUID.randomUUID().toString());
    }

    SchedulerLease(TicketingRedis bookingRedis, String instanceId) {
        this.redis = bookingRedis.template();
        this.instanceId = instanceId;
    }

    // 반환: 리스를 잡아 task를 실행했는지. task의 예외는 리스를 푼 뒤 그대로 던진다
    public boolean runIfLeader(String name, Duration ttl, Runnable task) {
        String key = KEY_PREFIX + name;
        Boolean acquired;
        try {
            acquired = redis.opsForValue().setIfAbsent(key, instanceId, ttl);
        } catch (DataAccessException | RedisException e) {
            log.debug("[booking lease] {} 리스 획득 실패, 이번 주기는 건너뛴다: {}", name, e.toString());
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

    public String instanceId() {
        return instanceId;
    }

    // TTL이 지나 다른 인스턴스가 잡은 리스는 값이 달라 지우지 않는다. 못 지우면 TTL이 푼다
    private void release(String name, String key) {
        try {
            redis.execute(RELEASE, List.of(key), instanceId);
        } catch (DataAccessException | RedisException e) {
            log.debug("[booking lease] {} 리스 해제 실패, TTL로 풀린다: {}", name, e.toString());
        }
    }

    private static DefaultRedisScript<Long> releaseScript() {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("scripts/booking/release.lua"));
        script.setResultType(Long.class);
        return script;
    }
}
