package com.example.ticketing.global.chaos;

import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * V5CB 서킷브레이커 데모용 장애 주입 엔드포인트. Redis 차단/복구만 노출한다.
 */
@Profile("!prod")
@RestController
@RequestMapping("/api/chaos")
@RequiredArgsConstructor
public class ChaosController {

    private final ChaosService chaosService;
    private final ChaosState chaosState;

    @PostMapping("/redis/block")
    public ResponseEntity<Void> redisBlock() {
        chaosService.blockRedis();
        return ResponseEntity.ok().build();
    }

    @PostMapping("/reset")
    public ResponseEntity<Void> reset() {
        chaosService.restoreRedis();
        return ResponseEntity.ok().build();
    }

    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> status() {
        return ResponseEntity.ok(Map.of("redisBlocked", chaosState.isRedisBlocked()));
    }
}
