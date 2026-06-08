package com.example.book_your_seat.queue.scheduler;

import com.example.book_your_seat.queue.service.command.QueueCommandService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class QueueScheduler {
    private final QueueCommandService queueCommandService;

    @Scheduled(fixedRate = 3 * 1000) //3초마다
    public void updateWaitingToProcessing() {
        queueCommandService.removeExpiredToken();
        queueCommandService.updateWaitingToProcessing();
        log.info("processing queue 가 update 되었습니다.");
    }
}
