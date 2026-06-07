package com.example.ticketing.reservation.controller;

import com.example.ticketing.global.resilience.CircuitBreakerStatsHolder;
import com.example.ticketing.reservation.dto.ReserveRequest;
import com.example.ticketing.reservation.dto.ReserveResponse;
import com.example.ticketing.reservation.service.v5cb.TicketServiceV5CB;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v5cb")
@RequiredArgsConstructor
public class ReserveControllerV5CB {

    private final TicketServiceV5CB ticketService;
    private final CircuitBreakerStatsHolder statsHolder;

    @PostMapping("/concerts/{concertId}/reserve")
    public ResponseEntity<ReserveResponse> reserve(
            @PathVariable Long concertId,
            @RequestBody @Valid ReserveRequest request) {
        return ResponseEntity.ok(ticketService.reserve(concertId, request.userId()));
    }

    @GetMapping("/stats")
    public ResponseEntity<Map<String, Object>> stats() {
        long redis    = statsHolder.getRedisPathCount();
        long fallback = statsHolder.getFallbackPathCount();
        long total    = statsHolder.getTotalCount();
        String ratio  = total > 0
                ? String.format("%.1f%%", fallback * 100.0 / total)
                : "0%";
        return ResponseEntity.ok(Map.of(
                "redisPathCount",    redis,
                "fallbackPathCount", fallback,
                "totalCount",        total,
                "fallbackRatio",     ratio
        ));
    }

    @PostMapping("/stats/reset")
    public ResponseEntity<Void> resetStats() {
        statsHolder.reset();
        return ResponseEntity.ok().<Void>build();
    }
}
