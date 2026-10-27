package com.example.ticketing.booking.common;

import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.boot.health.contributor.Status;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.stereotype.Component;

// Redis가 죽어도 선점·결제는 DB로 계속 되므로 DOWN 대신 DEGRADED(HTTP 200)로 알려 서버가 빠지지 않게 한다
@Component
public class BookingRedisHealthIndicator implements HealthIndicator {

    static final Status DEGRADED = new Status("DEGRADED", "Redis를 읽지 못해 DB 판정으로 동작 중");

    private final BookingRedis bookingRedis;

    public BookingRedisHealthIndicator(BookingRedis bookingRedis) {
        this.bookingRedis = bookingRedis;
    }

    // 예매 연결의 짧은 타임아웃으로 PING해 장애 때도 바로 답한다
    @Override
    public Health health() {
        try {
            bookingRedis.template().execute((RedisCallback<String>) RedisConnection::ping);
            return Health.up().build();
        } catch (RuntimeException e) {
            return Health.status(DEGRADED).withDetail("error", e.toString()).build();
        }
    }
}
