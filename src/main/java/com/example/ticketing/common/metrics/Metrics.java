package com.example.ticketing.common.metrics;

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

    public static final class Tag {
        public static final String RESULT = "result";
        public static final String OP = "op";
        public static final String SOURCE = "source";
        public static final String TYPE = "type";
        public static final String KIND = "kind";
        public static final String REASON = "reason";

        private Tag() {
        }
    }

    // HOLD_GATE·HOLD_DB의 result
    public static final class HoldResult {
        public static final String OK = "ok";
        public static final String SEAT_TAKEN = "seat_taken";
        public static final String USER_HAS_HOLD = "user_has_hold";
        public static final String COOLDOWN = "cooldown";
        public static final String SKIPPED = "skipped";
        public static final String ERROR = "error";
        public static final String STATE_CHANGED = "state_changed";

        private HoldResult() {
        }
    }

    // HOLD_FALLBACK의 result
    public static final class FallbackResult {
        public static final String ACQUIRED = "acquired";
        public static final String REJECTED = "rejected";

        private FallbackResult() {
        }
    }

    // SEATMAP_REBUILD의 source
    public static final class RebuildSource {
        public static final String SCHEDULER = "scheduler";
        public static final String MISS = "miss";
        public static final String FALLBACK = "fallback";

        private RebuildSource() {
        }
    }

    // PAYMENT_RECONCILE의 result
    public static final class ReconcileResult {
        public static final String CONFIRMED = "confirmed";
        public static final String SEAT_LOST = "seat_lost";
        public static final String DECLINED = "declined";
        public static final String CANCELLED = "cancelled";
        public static final String WAITING = "waiting";
        public static final String QUERY_FAILED = "query_failed";

        private ReconcileResult() {
        }
    }

    // OUTBOX_TASK의 result. type은 OutboxTaskType 이름이다
    public static final class OutboxResult {
        public static final String DONE = "done";
        public static final String RETRY = "retry";
        public static final String FAILED = "failed";

        private OutboxResult() {
        }
    }

    // REDIS_REFLECT_FAILURE의 op
    public static final class ReflectOp {
        public static final String RELEASE = "release";
        public static final String EXTEND = "extend";
        public static final String CONFIRM = "confirm";
        public static final String COOLDOWN = "cooldown";
        public static final String QUEUE_CONSUME = "queue_consume";

        private ReflectOp() {
        }
    }

    // GATE_ORPHAN_REMOVED의 kind
    public static final class OrphanKind {
        public static final String SEAT = "seat";
        public static final String USER_HOLD = "userhold";

        private OrphanKind() {
        }
    }

    // GATE_ORPHAN_SKIPPED의 reason
    public static final class SkipReason {
        public static final String FRESH = "fresh";

        private SkipReason() {
        }
    }
}
