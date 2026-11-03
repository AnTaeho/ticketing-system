package com.example.ticketing.common.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.HashMap;
import java.util.Map;

// 메트릭 이름·태그 키·태그 값은 여기서만 정한다. 운영 화면과 부하 스크립트가 이 문자열을 그대로 읽는다
public final class Metrics {

    public static final String HOLD_GATE = "ticketing.hold.gate";
    public static final String HOLD_DB = "ticketing.hold.db";
    public static final String HOLD_FALLBACK = "ticketing.hold.fallback";
    public static final String HOLD_EXPIRED = "ticketing.hold.expired";
    public static final String SEATMAP_REBUILD = "ticketing.seatmap.rebuild";
    public static final String PAYMENT_RECONCILE = "ticketing.payment.reconcile";
    public static final String OUTBOX_TASK = "ticketing.outbox.task";
    public static final String REDIS_REFLECT_FAILURE = "ticketing.redis.reflect.failure";
    public static final String GATE_ORPHAN_REMOVED = "ticketing.gate.orphan.removed";
    public static final String GATE_ORPHAN_SKIPPED = "ticketing.gate.orphan.skipped";

    private Metrics() {
    }

    // 태그 값마다 카운터를 미리 등록해 운영 화면에 0부터 보이게 한다. 키는 태그 값이다
    public static Map<String, Counter> counters(MeterRegistry registry, String name, String tagKey,
                                                String... tagValues) {
        Map<String, Counter> counters = new HashMap<>();
        for (String value : tagValues) {
            counters.put(value, registry.counter(name, tagKey, value));
        }
        return Map.copyOf(counters);
    }

    public interface Tag {
        String RESULT = "result";
        String OP = "op";
        String SOURCE = "source";
        String TYPE = "type";
        String KIND = "kind";
        String REASON = "reason";
    }

    // HOLD_GATE·HOLD_DB의 result
    public interface HoldResult {
        String OK = "ok";
        String SEAT_TAKEN = "seat_taken";
        String USER_HAS_HOLD = "user_has_hold";
        String COOLDOWN = "cooldown";
        String SKIPPED = "skipped";
        String ERROR = "error";
        String STATE_CHANGED = "state_changed";
    }

    // HOLD_FALLBACK의 result
    public interface FallbackResult {
        String ACQUIRED = "acquired";
        String REJECTED = "rejected";
    }

    // SEATMAP_REBUILD의 source
    public interface RebuildSource {
        String SCHEDULER = "scheduler";
        String MISS = "miss";
        String FALLBACK = "fallback";
    }

    // PAYMENT_RECONCILE의 result
    public interface ReconcileResult {
        String CONFIRMED = "confirmed";
        String SEAT_LOST = "seat_lost";
        String DECLINED = "declined";
        String CANCELLED = "cancelled";
        String WAITING = "waiting";
        String QUERY_FAILED = "query_failed";
    }

    // OUTBOX_TASK의 result. type은 OutboxTaskType 이름이다
    public interface OutboxResult {
        String DONE = "done";
        String RETRY = "retry";
        String FAILED = "failed";
    }

    // REDIS_REFLECT_FAILURE의 op
    public interface ReflectOp {
        String RELEASE = "release";
        String EXTEND = "extend";
        String CONFIRM = "confirm";
        String COOLDOWN = "cooldown";
        String QUEUE_CONSUME = "queue_consume";
    }

    // GATE_ORPHAN_REMOVED의 kind
    public interface OrphanKind {
        String SEAT = "seat";
        String USER_HOLD = "userhold";
    }

    // GATE_ORPHAN_SKIPPED의 reason
    public interface SkipReason {
        String FRESH = "fresh";
    }
}
