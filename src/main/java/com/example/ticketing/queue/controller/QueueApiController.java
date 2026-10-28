package com.example.ticketing.queue.controller;

import com.example.ticketing.common.web.CurrentUser;
import com.example.ticketing.queue.dto.QueueEnterResponse;
import com.example.ticketing.queue.dto.QueueStatusResponse;
import com.example.ticketing.queue.service.QueueService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class QueueApiController {

    private final QueueService queueService;

    @PostMapping("/api/performances/{performanceId}/queue/tokens")
    public QueueEnterResponse enter(@CurrentUser Long userId, @PathVariable long performanceId) {
        return QueueEnterResponse.from(queueService.enter(userId, performanceId));
    }

    @GetMapping("/api/performances/{performanceId}/queue/tokens/{queueToken}")
    public QueueStatusResponse status(@CurrentUser Long userId, @PathVariable long performanceId,
                                      @PathVariable String queueToken) {
        return QueueStatusResponse.from(queueService.status(userId, performanceId, queueToken));
    }
}
