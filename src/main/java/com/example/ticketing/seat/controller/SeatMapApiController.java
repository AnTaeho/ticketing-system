package com.example.ticketing.seat.controller;

import com.example.ticketing.seat.service.SeatMapQueryService;
import java.nio.charset.StandardCharsets;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class SeatMapApiController {

    private static final MediaType JSON_UTF8 = new MediaType(MediaType.APPLICATION_JSON, StandardCharsets.UTF_8);

    private final SeatMapQueryService seatMapQueryService;

    // 스냅샷 JSON을 다시 파싱하지 않고 그대로 낸다. 매초 바뀌므로 브라우저가 저장해 두지 않게 한다
    @GetMapping("/api/performances/{performanceId}/seats")
    public ResponseEntity<String> seats(@PathVariable long performanceId) {
        return ResponseEntity.ok()
                .contentType(JSON_UTF8)
                .cacheControl(CacheControl.noStore())
                .body(seatMapQueryService.get(performanceId));
    }
}
