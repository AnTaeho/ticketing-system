package com.example.ticketing.common.redis;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.script.RedisScript;

// 스크립트 본문과 SHA를 클래스 로딩 때 한 번만 만든다. 파일이 없으면 기동부터 실패한다
public final class RedisScripts {

    @SuppressWarnings("rawtypes")
    public static final RedisScript<List> HOLD = load("hold.lua", List.class);
    public static final RedisScript<Long> EXTEND = load("extend.lua", Long.class);
    public static final RedisScript<Long> CONFIRM = load("confirm.lua", Long.class);
    public static final RedisScript<Long> RELEASE = load("release.lua", Long.class);
    @SuppressWarnings("rawtypes")
    public static final RedisScript<List> QUEUE_ENTER = load("queue_enter.lua", List.class);
    public static final RedisScript<Long> QUEUE_PROMOTE = load("queue_promote.lua", Long.class);
    public static final RedisScript<Long> QUEUE_CONSUME = load("queue_consume.lua", Long.class);
    public static final RedisScript<Long> QUEUE_HEARTBEAT = load("queue_heartbeat.lua", Long.class);
    public static final RedisScript<Long> QUEUE_LEAVE = load("queue_leave.lua", Long.class);
    public static final RedisScript<Long> QUEUE_PURGE = load("queue_purge.lua", Long.class);
    public static final RedisScript<Long> SEATMAP_LEASE = load("seatmap_lease.lua", Long.class);

    private RedisScripts() {
    }

    private static <T> RedisScript<T> load(String name, Class<T> resultType) {
        ClassPathResource resource = new ClassPathResource("scripts/booking/" + name);
        try {
            return RedisScript.of(resource.getContentAsString(StandardCharsets.UTF_8), resultType);
        } catch (IOException e) {
            throw new UncheckedIOException("Redis 스크립트를 읽지 못했다: " + name, e);
        }
    }
}
