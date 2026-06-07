package com.example.ticketing.global.chaos;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

@Profile("!prod")
@RestController
@RequestMapping("/api/chaos")
@RequiredArgsConstructor
public class ChaosController {

    private final ChaosService chaosService;
    private final ChaosState chaosState;

    @PostMapping("/hikari/constrain")
    public ResponseEntity<Void> constrainHikari(@RequestParam int maxPoolSize) {
        chaosService.constrainHikari(maxPoolSize);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/hikari/restore")
    public ResponseEntity<Void> restoreHikari() {
        chaosService.restoreHikari();
        return ResponseEntity.ok().build();
    }

    @PostMapping("/redis/delay")
    public ResponseEntity<Void> redisDelay(@RequestParam long ms) {
        chaosService.setRedisDelay(ms);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/redis/block")
    public ResponseEntity<Void> redisBlock() {
        chaosService.setRedisBlock();
        return ResponseEntity.ok().build();
    }

    @PostMapping("/redis/restore")
    public ResponseEntity<Void> restoreRedis() {
        chaosService.restoreRedis();
        return ResponseEntity.ok().build();
    }

    @PostMapping("/kafka/pause")
    public ResponseEntity<Void> pauseKafka() {
        chaosService.pauseKafka();
        return ResponseEntity.ok().build();
    }

    @PostMapping("/kafka/resume")
    public ResponseEntity<Void> resumeKafka() {
        chaosService.resumeKafka();
        return ResponseEntity.ok().build();
    }

    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> status() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("hikari", Map.of(
                "constrained", chaosState.isHikariConstrained(),
                "maxPoolSize", chaosState.getHikariMaxPoolSize(),
                "originalMaxPoolSize", chaosState.getOriginalMaxPoolSize()
        ));
        result.put("redis", Map.of(
                "mode", chaosState.getRedisMode().name(),
                "delayMs", chaosState.getRedisDelayMs()
        ));
        result.put("kafka", Map.of("paused", chaosState.isKafkaPaused()));
        return ResponseEntity.ok(result);
    }

    @PostMapping("/reset")
    public ResponseEntity<Void> reset() {
        chaosService.resetAll();
        return ResponseEntity.ok().build();
    }
}
