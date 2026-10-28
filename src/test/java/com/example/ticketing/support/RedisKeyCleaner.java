package com.example.ticketing.support;

import com.example.ticketing.common.redis.TicketingRedis;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;

// 테스트가 만든 회차 키를 지운다. 운영 코드에 정리 메서드를 두지 않으려고 테스트 쪽에 둔다
@TestComponent
@RequiredArgsConstructor
public class RedisKeyCleaner {

    private final TicketingRedis bookingRedis;

    public List<String> keys(long performanceId) {
        StringRedisTemplate redis = bookingRedis.template();
        List<String> keys = new ArrayList<>();
        ScanOptions options = ScanOptions.scanOptions().match("*{p:" + performanceId + "}*").count(1000).build();
        try (Cursor<String> cursor = redis.scan(options)) {
            cursor.forEachRemaining(keys::add);
        }
        return keys;
    }

    // 대기열 키(bq:)를 뺀 게이트 키만. 처리열 토큰은 게이트 ON/OFF와 무관하게 남는다
    public List<String> gateKeys(long performanceId) {
        return keys(performanceId).stream().filter(key -> !key.startsWith("bq:")).toList();
    }

    public void clean(long performanceId) {
        List<String> keys = keys(performanceId);
        if (!keys.isEmpty()) {
            bookingRedis.template().delete(keys);
        }
    }
}
