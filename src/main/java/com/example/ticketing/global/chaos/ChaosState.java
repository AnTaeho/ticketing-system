package com.example.ticketing.global.chaos;

import org.springframework.stereotype.Component;

/**
 * 카오스 주입 상태. V5CB 서킷브레이커 데모에 필요한 Redis 차단 한 가지만 보유한다.
 * (초기엔 Hikari 풀 제한·Redis 지연·Kafka pause까지 있었으나, CB 검증에 불필요해 제거)
 */
@Component
public class ChaosState {

    private volatile boolean redisBlocked = false;

    public boolean isRedisBlocked() { return redisBlocked; }

    public void setRedisBlocked(boolean redisBlocked) { this.redisBlocked = redisBlocked; }
}
