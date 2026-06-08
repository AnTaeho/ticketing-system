package com.example.book_your_seat.queue.controller;

import com.example.book_your_seat.config.security.auth.LoginUser;
import com.example.book_your_seat.queue.controller.dto.QueueResponse;
import com.example.book_your_seat.queue.controller.dto.TokenResponse;
import com.example.book_your_seat.queue.service.command.QueueCommandService;
import com.example.book_your_seat.queue.service.query.QueueQueryService;
import com.example.book_your_seat.user.domain.User;
import io.swagger.v3.oas.annotations.Operation;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/queues")
@RequiredArgsConstructor
public class QueueController {

    private final QueueCommandService queueCommandService;
    private final QueueQueryService queueQueryService;

    @Operation(
            summary = "토큰을 발행하고 대기열에 진입합니다.",
            description = "토큰을 발행하고 대기열에 진입합니다."
    )
    @PostMapping("/token/{concertId}")
    public ResponseEntity<TokenResponse> issueTokenAndEnqueue(@LoginUser User user, @PathVariable("concertId") Long concertId) {
        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(queueCommandService.issueTokenAndEnqueue(user.getId(), concertId));
    }

    @Operation(
            summary = "큐와 토큰 정보를 요청합니다.",
            description = "큐와 토큰 정보를 요청합니다."
    )
    @GetMapping("/{concertId}")
    public ResponseEntity<QueueResponse> getQueueInfoWithToken(@PathVariable("concertId") Long concertId,
                                                               @RequestParam("token") String token) {
        return ResponseEntity.ok()
                .body(queueQueryService.findQueueStatus(concertId, token));
    }

    @Operation(
            summary = "대기 큐를 빠져 나옵니다.",
            description = "대기 큐를 빠져 나옵니다."
    )
    @PostMapping("/wait/quit/{concertId}")
    public ResponseEntity<Void> dequeueWaitingQueue(@LoginUser User user, @PathVariable("concertId") Long concertId,
                                                    @RequestParam("token") String token) {
        queueCommandService.removeTokenInWaitingQueue(user.getId(), concertId, token);
        return ResponseEntity.ok(null);
    }

    @Operation(
            summary = "프로세싱 큐를 빠져 나옵니다.",
            description = "프로세싱 큐를 빠져 나옵니다."
    )
    @PostMapping("/process/quit/{concertId}")
    public ResponseEntity<Void> dequeueProcessingQueue(@LoginUser User user, @PathVariable("concertId") Long concertId,
                                                       @RequestParam("token") String token) {
        queueCommandService.removeTokenInProcessingQueue(user.getId(), concertId, token);
        return ResponseEntity.ok(null);
    }
}
