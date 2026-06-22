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

/**
 * 부하 테스트/데모 전용 유틸 API.
 *
 * <p>재고 초기화는 운영 데이터를 임의로 덮어쓰는 위험한 작업이므로 {@code prod} 프로파일에서는
 * 빈 자체가 등록되지 않아 엔드포인트가 비활성화된다(404). {@code @Profile}은 핸들러 메서드 단위로는
 * 동작하지 않으므로, 운영에서 살아있어야 하는 조회 API({@link ConcertController})와 분리해 둔다.
 */
@Profile("!prod")
@Validated
@RestController
@RequestMapping("/api/concerts")
@RequiredArgsConstructor
public class ConcertTestController {

    private final ConcertService concertService;

    // 공통 유틸 — 재고 초기화 (테스트용, prod 비활성화)
    @PostMapping("/{concertId}/reset")
    public ResponseEntity<Map<String, String>> resetStock(
            @PathVariable Long concertId,
            @Positive @RequestParam(defaultValue = "100") int stock) {
        concertService.resetStock(concertId, stock);
        return ResponseEntity.ok(Map.of("result", "재고가 " + stock + "으로 초기화되었습니다."));
    }
}
