package com.example.ticketing.booking.common;

import com.example.ticketing.booking.BookingProperties;

// 생성자가 패키지 전용이라 다른 패키지 테스트가 장애용 연결(닫힌 포트)을 만들 때 쓴다
public final class TestBookingRedis {

    private TestBookingRedis() {
    }

    public static BookingRedis connectTo(String host, int port, BookingProperties props) {
        return new BookingRedis(host, port, props);
    }
}
