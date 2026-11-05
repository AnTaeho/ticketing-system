package com.example.ticketing.support;

import com.example.ticketing.common.config.TicketingProperties;
import com.example.ticketing.common.config.TicketingProperties.Gate;
import com.example.ticketing.common.config.TicketingProperties.Hold;
import com.example.ticketing.common.config.TicketingProperties.Outbox;
import com.example.ticketing.common.config.TicketingProperties.Payment;
import com.example.ticketing.common.config.TicketingProperties.Pg;

// 설정을 바꾼 대상은 컨텍스트를 늘리지 않고 이 사본으로 new 조립한다. 설정 record가 바뀌면 empty()와 with()만 고친다
public final class TestProperties {

    private TicketingProperties p;

    private TestProperties(TicketingProperties p) {
        this.p = p;
    }

    public static TestProperties from(TicketingProperties p) {
        return new TestProperties(p);
    }

    // 컨텍스트 없는 단위 테스트용. 쓰는 묶음만 채운다
    public static TestProperties empty() {
        return new TestProperties(
                new TicketingProperties(null, null, null, null, null, null, null, null));
    }

    public TestProperties hold(Hold hold) {
        return with(hold, p.payment(), p.gate(), p.outbox(), p.pg());
    }

    public TestProperties payment(Payment payment) {
        return with(p.hold(), payment, p.gate(), p.outbox(), p.pg());
    }

    public TestProperties gate(Gate gate) {
        return with(p.hold(), p.payment(), gate, p.outbox(), p.pg());
    }

    public TestProperties outbox(Outbox outbox) {
        return with(p.hold(), p.payment(), p.gate(), outbox, p.pg());
    }

    public TestProperties gateEnabled(boolean enabled) {
        Gate g = p.gate();
        return gate(new Gate(enabled, g.fallbackMaxConcurrency(), g.reconcileEnabled(), g.reconcileScanMs()));
    }

    public TestProperties fallbackMaxConcurrency(int max) {
        Gate g = p.gate();
        return gate(new Gate(g.enabled(), max, g.reconcileEnabled(), g.reconcileScanMs()));
    }

    public TestProperties outboxBatchSize(int batchSize) {
        Outbox o = p.outbox();
        return outbox(new Outbox(o.workerEnabled(), o.pollIntervalMs(), batchSize, o.lockDuration(), o.maxAttempts(),
                o.maxBackoff()));
    }

    public TestProperties pgBaseUrl(String baseUrl) {
        Pg pg = p.pg();
        return with(p.hold(), p.payment(), p.gate(), p.outbox(),
                new Pg(baseUrl, pg.connectTimeout(), pg.readTimeout()));
    }

    public TicketingProperties build() {
        return p;
    }

    private TestProperties with(Hold hold, Payment payment, Gate gate, Outbox outbox, Pg pg) {
        p = new TicketingProperties(p.seed(), hold, payment, gate, p.seatmap(), outbox, pg, p.fakePg());
        return this;
    }
}
