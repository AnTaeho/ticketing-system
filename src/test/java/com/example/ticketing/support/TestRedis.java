package com.example.ticketing.support;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;

// 테스트가 Redis 키를 살피고 지우는 도구와 장애용 연결. 운영 코드에 정리 메서드를 두지 않으려고 여기에 둔다
@TestComponent
public class TestRedis {

    private final StringRedisTemplate redis;
    private final List<LettuceConnectionFactory> deadConnections = new CopyOnWriteArrayList<>();

    public TestRedis(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public StringRedisTemplate template() {
        return redis;
    }

    // 남은 수명(ms). 키가 없으면 -2, 수명이 없으면 -1
    public long pttl(String key) {
        Long ttl = template().getExpire(key, TimeUnit.MILLISECONDS);
        return ttl == null ? -3 : ttl;
    }

    // 회차 해시 태그가 붙은 키 전부
    public List<String> keys(long performanceId) {
        List<String> keys = new ArrayList<>();
        ScanOptions options = ScanOptions.scanOptions().match("*{p:" + performanceId + "}*").count(1000).build();
        try (Cursor<String> cursor = template().scan(options)) {
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
            template().delete(keys);
        }
    }

    // 운영 연결과 같은 설정으로 닫힌 포트에 붙여 모든 명령이 Redis 장애로 실패한다. 테스트가 끝나면 닫는다
    public StringRedisTemplate dead() {
        LettuceConnectionFactory live = (LettuceConnectionFactory) redis.getConnectionFactory();
        LettuceConnectionFactory dead = new LettuceConnectionFactory(
                new RedisStandaloneConfiguration("localhost", closedPort()), live.getClientConfiguration());
        dead.afterPropertiesSet();
        dead.start();
        deadConnections.add(dead);
        return new StringRedisTemplate(dead);
    }

    void cleanUp() {
        for (LettuceConnectionFactory dead : deadConnections) {
            dead.destroy();
        }
        deadConnections.clear();
    }

    private static int closedPort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
