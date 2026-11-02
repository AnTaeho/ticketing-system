package com.example.ticketing.hold.service;

import com.example.ticketing.common.config.TicketingProperties;
import com.example.ticketing.support.IntegrationTest;
import com.example.ticketing.support.TestProperties;
import com.example.ticketing.support.TestServices;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;

// 같은 동시성 테스트를 Redis 게이트 없이 돌린다. DB 판정만으로도 성공 1이고 Redis 키가 생기지 않아야 한다
@IntegrationTest
class SeatHoldConcurrencyGateOffTest extends SeatHoldConcurrencyTest {

    @Autowired
    private TestServices services;
    @Autowired
    private TicketingProperties properties;

    private SeatHoldService gateOff;

    @BeforeEach
    void setUpGateOff() {
        TicketingProperties off = TestProperties.from(properties).gateEnabled(false).build();
        gateOff = services.seatHoldService().properties(off).build();
    }

    @Override
    protected SeatHoldService service() {
        return gateOff;
    }

    @Override
    protected boolean gateEnabled() {
        return false;
    }
}
