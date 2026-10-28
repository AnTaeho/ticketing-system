package com.example.ticketing.common.redis;

import com.example.ticketing.common.config.TicketingProperties;

// 생성자가 패키지 전용이라 다른 패키지 테스트가 장애용 연결(닫힌 포트)을 만들 때 쓴다
public final class TestTicketingRedis {

    private TestTicketingRedis() {
    }

    public static TicketingRedis connectTo(String host, int port, TicketingProperties properties) {
        return new TicketingRedis(host, port, properties);
    }
}
