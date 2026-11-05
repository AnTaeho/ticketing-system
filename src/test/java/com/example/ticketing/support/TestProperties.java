package com.example.ticketing.support;

import com.example.ticketing.common.config.TicketingProperties;
import com.example.ticketing.common.config.TicketingProperties.Gate;
import com.example.ticketing.common.config.TicketingProperties.Hold;
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
                new TicketingProperties(null, null, null, null, null, null));
    }

    public TestProperties hold(Hold hold) {
        return with(hold, p.payment(), p.gate(), p.pg());
    }

    public TestProperties payment(Payment payment) {
        return with(p.hold(), payment, p.gate(), p.pg());
    }

    public TestProperties gate(Gate gate) {
        return with(p.hold(), p.payment(), gate, p.pg());
    }

    public TestProperties gateEnabled(boolean enabled) {
        return gate(new Gate(enabled));
    }

    public TestProperties pgBaseUrl(String baseUrl) {
        Pg pg = p.pg();
        return with(p.hold(), p.payment(), p.gate(),
                new Pg(baseUrl, pg.connectTimeout(), pg.readTimeout()));
    }

    public TicketingProperties build() {
        return p;
    }

    private TestProperties with(Hold hold, Payment payment, Gate gate, Pg pg) {
        p = new TicketingProperties(p.seed(), hold, payment, gate, pg, p.fakePg());
        return this;
    }
}
