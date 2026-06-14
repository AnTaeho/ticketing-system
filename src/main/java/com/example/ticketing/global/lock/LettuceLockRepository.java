package com.example.ticketing.global.lock;

import java.time.Duration;
import java.util.Collections;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class LettuceLockRepository {

    private static final String LOCK_KEY_PREFIX = "lock:concert:";

    /**
     * 락 TTL. 데드락 방지를 위한 고정 만료이며 <b>워치독(자동 연장)이 없다</b>.
     *
     * <p><b>트레이드오프(의도된 한계):</b> 트랜잭션이 TTL(3초)을 초과하면 작업 도중 락이 만료되어
     * 다른 스레드가 진입할 수 있고, 두 스레드가 동시에 재고를 차감해 오버부킹이 발생할 수 있다.
     * Lua compare-and-delete({@link #RELEASE_SCRIPT})로 <i>남의 락을 삭제하는</i> 문제는 막았지만,
     * <i>실행 도중 만료</i> 자체는 막지 못한다(Lettuce 단순 SETNX 방식의 본질적 한계).
     *
     * <p>이 한계를 해소한 것이 V5(Redisson)다. 워치독이 leaseTime을 주기적으로 자동 갱신해
     * 작업이 끝날 때까지 락이 유지된다. 즉 "구현 단순성(V4) ↔ 실행 중 만료 안전성(V5)"의 트레이드오프.
     */
    private static final Duration LOCK_TTL = Duration.ofSeconds(3);

    private static final DefaultRedisScript<Long> RELEASE_SCRIPT = new DefaultRedisScript<>(
            "if redis.call('get',KEYS[1]) == ARGV[1] then return redis.call('del',KEYS[1]) else return 0 end",
            Long.class
    );

    private final StringRedisTemplate redisTemplate;

    public String tryLock(Long concertId) {
        String lockValue = UUID.randomUUID().toString();
        Boolean acquired = redisTemplate.opsForValue()
                .setIfAbsent(buildKey(concertId), lockValue, LOCK_TTL);
        return Boolean.TRUE.equals(acquired) ? lockValue : null;
    }

    public void releaseLock(Long concertId, String lockValue) {
        redisTemplate.execute(RELEASE_SCRIPT, Collections.singletonList(buildKey(concertId)), lockValue);
    }

    private String buildKey(Long concertId) {
        return LOCK_KEY_PREFIX + concertId;
    }
}
