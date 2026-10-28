package com.example.ticketing.ops.controller;

import com.example.ticketing.ops.dto.OpsSummary;
import com.example.ticketing.ops.service.OpsSummaryService;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

// 시연 옆 창에서 로그인 없이 띄우려고 사용자 확인을 두지 않는다. prod에서는 빈이 없어 경로도 없다
@Profile("!prod")
@RestController
@RequiredArgsConstructor
public class OpsApiController {

    private final OpsSummaryService opsSummaryService;

    @GetMapping("/api/ops/performances/{performanceId}/summary")
    public OpsSummary summary(@PathVariable long performanceId) {
        return opsSummaryService.summarize(performanceId);
    }
}
