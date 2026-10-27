package com.example.ticketing.booking.queue.api;

import com.example.ticketing.booking.common.CurrentUser;
import com.example.ticketing.booking.queue.BookingQueueService;
import com.example.ticketing.booking.queue.dto.QueueEntered;
import com.example.ticketing.booking.queue.dto.QueuePosition;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class QueueApiController {

    private final BookingQueueService queueService;

    @PostMapping("/api/performances/{performanceId}/queue/tokens")
    public QueueEntered enter(@CurrentUser Long userId, @PathVariable long performanceId) {
        return queueService.enter(performanceId, userId);
    }

    @GetMapping("/api/performances/{performanceId}/queue/tokens/{token}")
    public QueuePosition status(@CurrentUser Long userId, @PathVariable long performanceId,
                                @PathVariable String token) {
        return queueService.status(performanceId, userId, token);
    }
}
