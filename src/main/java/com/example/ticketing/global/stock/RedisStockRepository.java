package com.example.ticketing.global.stock;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class RedisStockRepository {

    private static final String STOCK_KEY_PREFIX = "stock:concert:";

    private final StringRedisTemplate redisTemplate;

    public void initStock(Long concertId, int stock) {
        redisTemplate.opsForValue().set(buildKey(concertId), String.valueOf(stock));
        log.info("[Redis] 재고 초기화 - concertId={}, stock={}", concertId, stock);
    }

    public long decrement(Long concertId) {
        return redisTemplate.opsForValue().decrement(buildKey(concertId));
    }

    public void increment(Long concertId) {
        redisTemplate.opsForValue().increment(buildKey(concertId));
    }

    public long getStock(Long concertId) {
        String val = redisTemplate.opsForValue().get(buildKey(concertId));
        return val == null ? 0L : Long.parseLong(val);
    }

    private String buildKey(Long concertId) {
        return STOCK_KEY_PREFIX + concertId;
    }
}
