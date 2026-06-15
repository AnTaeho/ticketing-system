package com.example.ticketing.global.chaos;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * V5CB 서킷브레이커 데모용 장애 주입. Redis 차단/복구만 제공한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChaosService {

    private final ChaosState chaosState;

    public void blockRedis() {
        chaosState.setRedisBlocked(true);
        log.info("[Chaos] Redis 차단");
    }

    public void restoreRedis() {
        chaosState.setRedisBlocked(false);
        log.info("[Chaos] Redis 복구");
    }
}
