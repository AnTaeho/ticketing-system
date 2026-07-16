package com.example.ticketing.concert.controller;

import com.example.ticketing.concert.service.ConcertService;
import jakarta.validation.constraints.Positive;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Profile("!prod")
@Validated
@RestController
@RequestMapping("/api/concerts")
@RequiredArgsConstructor
public class ConcertTestController {

    private final ConcertService concertService;

    @PostMapping("/{concertId}/reset")
    public ResponseEntity<Map<String, String>> resetStock(
            @PathVariable Long concertId,
            @Positive @RequestParam(defaultValue = "100") int stock) {
        concertService.resetStock(concertId, stock);
        return ResponseEntity.ok(Map.of("result", "재고가 " + stock + "으로 초기화되었습니다."));
    }
}
