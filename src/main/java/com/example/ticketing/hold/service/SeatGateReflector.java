package com.example.ticketing.hold.service;

import com.example.ticketing.common.config.TicketingProperties;
import com.example.ticketing.common.redis.RedisFailures;
import com.example.ticketing.common.redis.RedisUnavailableException;
import com.example.ticketing.hold.domain.GateHold;
import com.example.ticketing.hold.repository.SeatGateRedisStore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

// DB 커밋 뒤 게이트 키 해제. 게이트가 꺼져 있으면 Redis를 부르지 않는다
// 실패해도 사용자 응답을 바꾸지 않는다. 남은 키는 TTL로 사라지고 그동안에도 DB가 판정한다
@Slf4j
@Component
public class SeatGateReflector {

    private final SeatGateRedisStore store;
    private final boolean enabled;

    public SeatGateReflector(SeatGateRedisStore store, TicketingProperties properties) {
        this.store = store;
        this.enabled = properties.gate().enabled();
    }

    // 게이트 스위치(ticketing.gate.enabled)는 여기서만 읽는다
    public boolean enabled() {
        return enabled;
    }

    public void release(GateHold hold) {
        if (!enabled || hold.seatIds().isEmpty()) {
            return;
        }
        try {
            store.release(hold);
        } catch (RedisUnavailableException e) {
            log.warn("[gate] Redis 해제 실패 performanceId={} cause={}", hold.performanceId(),
                    RedisFailures.describe(e));
        }
    }
}
