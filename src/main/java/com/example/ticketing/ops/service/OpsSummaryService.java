package com.example.ticketing.ops.service;

import com.example.ticketing.common.log.ThrottledWarn;
import com.example.ticketing.common.redis.RedisFailures;
import com.example.ticketing.common.redis.RedisUnavailableException;
import com.example.ticketing.ops.dto.OpsDbSnapshot;
import com.example.ticketing.ops.dto.OpsSummary;
import com.example.ticketing.ops.dto.OpsSummary.GateKeys;
import com.example.ticketing.ops.dto.OpsSummary.Queue;
import com.example.ticketing.ops.repository.OpsRedisStore;
import java.util.function.LongSupplier;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

// 시연용 집계. DB 칸은 읽기 트랜잭션 하나로 센 뒤, Redis 칸은 트랜잭션 밖에서 읽는다
@Slf4j
@Service
public class OpsSummaryService {

    private final OpsSummaryReader summaryReader;
    private final OpsRedisStore opsRedisStore;
    private final OpsMetricsReader metricsReader;
    private final ThrottledWarn redisWarn;

    public OpsSummaryService(OpsSummaryReader summaryReader, OpsRedisStore opsRedisStore,
                             OpsMetricsReader metricsReader, LongSupplier nanoClock) {
        this.summaryReader = summaryReader;
        this.opsRedisStore = opsRedisStore;
        this.metricsReader = metricsReader;
        this.redisWarn = new ThrottledWarn(log, nanoClock);
    }

    public OpsSummary summarize(long performanceId) {
        OpsDbSnapshot db = summaryReader.read(performanceId);
        RedisPart redis = readRedis(performanceId);
        return new OpsSummary(db.generatedAt(), db.performance(), db.seats(), db.holds(), db.payments(),
                db.bookings(), db.outbox(), db.attention(), redis.available(), redis.queue(), redis.gateKeys(),
                metricsReader.counterValues());
    }

    // 한 명령이라도 실패하면 Redis 칸 전체를 비운다. 연결이 죽었으면 나머지도 타임아웃까지 기다리게 되므로
    private RedisPart readRedis(long performanceId) {
        try {
            Queue queue = new Queue(opsRedisStore.waitingCount(performanceId),
                    opsRedisStore.processingCount(performanceId));
            GateKeys gateKeys = new GateKeys(opsRedisStore.seatKeyCount(performanceId),
                    opsRedisStore.userHoldKeyCount(performanceId));
            return new RedisPart(true, queue, gateKeys);
        } catch (RedisUnavailableException e) {
            redisWarn.warn("[ops] Redis 집계 실패 performanceId={} cause={}", performanceId, RedisFailures.describe(e));
            return new RedisPart(false, new Queue(null, null), new GateKeys(null, null));
        }
    }

    private record RedisPart(boolean available, Queue queue, GateKeys gateKeys) {
    }
}
