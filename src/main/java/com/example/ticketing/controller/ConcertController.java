package com.example.ticketing.controller;

import com.example.ticketing.domain.Concert;
import com.example.ticketing.service.ConcertService;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/concerts")
@RequiredArgsConstructor
public class ConcertController {

    private final ConcertService concertService;

    // 시나리오 B — 공연 목록 (페이징)
    @GetMapping
    public ResponseEntity<List<Concert>> getConcerts(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(concertService.getConcerts(page, size));
    }

    // 시나리오 B — 공연 상세 + 잔여석 수
    @GetMapping("/{concertId}")
    public ResponseEntity<Concert> getConcert(@PathVariable Long concertId) {
        return ResponseEntity.ok(concertService.getConcert(concertId));
    }

    // 공통 유틸 — 현재 재고 조회
    @GetMapping("/{concertId}/stock")
    public ResponseEntity<Map<String, Integer>> getStock(@PathVariable Long concertId) {
        return ResponseEntity.ok(Map.of("stock", concertService.getStock(concertId)));
    }

    // 공통 유틸 — 재고 초기화 (테스트용)
    @PostMapping("/{concertId}/reset")
    public ResponseEntity<Map<String, String>> resetStock(
            @PathVariable Long concertId,
            @RequestParam(defaultValue = "100") int stock) {
        concertService.resetStock(concertId, stock);
        return ResponseEntity.ok(Map.of("result", "재고가 " + stock + "으로 초기화되었습니다."));
    }
}
