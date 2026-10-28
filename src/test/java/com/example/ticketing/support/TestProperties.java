package com.example.ticketing.support;

import com.example.ticketing.common.config.TicketingProperties;
import com.example.ticketing.common.config.TicketingProperties.Gate;
import com.example.ticketing.common.config.TicketingProperties.Hold;
import com.example.ticketing.common.config.TicketingProperties.Outbox;
import com.example.ticketing.common.config.TicketingProperties.Payment;
import com.example.ticketing.common.config.TicketingProperties.Pg;
import com.example.ticketing.common.config.TicketingProperties.Queue;

// 설정을 바꾼 대상은 컨텍스트를 늘리지 않고 이 사본으로 new 조립한다. 설정 record가 바뀌면 이 파일만 고친다
public final class TestProperties {

    private TicketingProperties.Seed seed;
    private TicketingProperties.Redis redis;
    private Hold hold;
    private Payment payment;
    private Gate gate;
    private Queue queue;
    private TicketingProperties.Seatmap seatmap;
    private Outbox outbox;
    private TicketingProperties.Ops ops;
    private Pg pg;
    private TicketingProperties.FakePg fakePg;

    private TestProperties() {
    }

    public static TestProperties from(TicketingProperties p) {
        TestProperties copy = new TestProperties();
        copy.seed = p.seed();
        copy.redis = p.redis();
        copy.hold = p.hold();
        copy.payment = p.payment();
        copy.gate = p.gate();
        copy.queue = p.queue();
        copy.seatmap = p.seatmap();
        copy.outbox = p.outbox();
        copy.ops = p.ops();
        copy.pg = p.pg();
        copy.fakePg = p.fakePg();
        return copy;
    }

    // 컨텍스트 없는 단위 테스트용. 쓰는 묶음만 채운다
    public static TestProperties empty() {
        return new TestProperties();
    }

    public TestProperties hold(Hold hold) {
        this.hold = hold;
        return this;
    }

    public TestProperties payment(Payment payment) {
        this.payment = payment;
        return this;
    }

    public TestProperties gate(Gate gate) {
        this.gate = gate;
        return this;
    }

    public TestProperties outbox(Outbox outbox) {
        this.outbox = outbox;
        return this;
    }

    public TestProperties gateEnabled(boolean enabled) {
        this.gate = new Gate(enabled, gate.fallbackMaxConcurrency());
        return this;
    }

    public TestProperties fallbackMaxConcurrency(int max) {
        this.gate = new Gate(gate.enabled(), max);
        return this;
    }

    public TestProperties queueCapacity(int capacity) {
        Queue q = queue;
        this.queue = new Queue(capacity, q.processingTokenTtl(), q.waitingTokenTtl(), q.promoteEnabled(),
                q.promoteIntervalMs(), q.required());
        return this;
    }

    public TestProperties queueRequired(boolean required) {
        Queue q = queue;
        this.queue = new Queue(q.capacity(), q.processingTokenTtl(), q.waitingTokenTtl(), q.promoteEnabled(),
                q.promoteIntervalMs(), required);
        return this;
    }

    public TestProperties outboxBatchSize(int batchSize) {
        Outbox o = outbox;
        this.outbox = new Outbox(o.workerEnabled(), o.pollIntervalMs(), batchSize, o.lockDuration(), o.maxAttempts(),
                o.maxBackoff());
        return this;
    }

    public TestProperties pgBaseUrl(String baseUrl) {
        this.pg = new Pg(baseUrl, pg.connectTimeout(), pg.readTimeout());
        return this;
    }

    public TicketingProperties build() {
        return new TicketingProperties(seed, redis, hold, payment, gate, queue, seatmap, outbox, ops, pg, fakePg);
    }
}
