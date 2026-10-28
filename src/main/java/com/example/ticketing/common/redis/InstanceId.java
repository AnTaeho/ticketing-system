package com.example.ticketing.common.redis;

import java.util.UUID;
import org.springframework.stereotype.Component;

// 리스 값으로 쓰는 이 서버의 소유자 ID. 기동마다 새로 만들어 재시작 전의 리스와 섞이지 않게 한다
@Component
public final class InstanceId {

    private final String value = UUID.randomUUID().toString();

    public String value() {
        return value;
    }
}
