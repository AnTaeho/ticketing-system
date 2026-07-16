package com.example.ticketing.waitingroom.controller;

import com.example.ticketing.waitingroom.dto.QueueStatusResponse;
import com.example.ticketing.waitingroom.dto.QueueTokenResponse;
import com.example.ticketing.waitingroom.service.QueueCommandService;
import com.example.ticketing.waitingroom.service.QueueQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/waitingroom/concerts/{concertId}/queue")
@RequiredArgsConstructor
public class QueueController {

    private final QueueCommandService queueCommandService;
    private final QueueQueryService queueQueryService;

    @PostMapping("/token")
    public ResponseEntity<QueueTokenResponse> issueToken(
            @PathVariable Long concertId,
            @RequestParam Long userId) {
        return ResponseEntity.ok(queueCommandService.issueTokenAndEnqueue(userId, concertId));
    }

    @GetMapping("/status")
    public ResponseEntity<QueueStatusResponse> getStatus(
            @PathVariable Long concertId,
            @RequestParam String token) {
        return ResponseEntity.ok(queueQueryService.getQueueStatus(concertId, token));
    }
}
