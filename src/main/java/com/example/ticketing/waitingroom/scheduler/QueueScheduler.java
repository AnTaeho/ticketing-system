package com.example.ticketing.waitingroom.scheduler;

import com.example.ticketing.waitingroom.service.QueueCommandService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class QueueScheduler {

    private final QueueCommandService queueCommandService;

    @Scheduled(fixedRate = 3_000)
    public void processQueue() {
        queueCommandService.purgeExpiredTokens();
        queueCommandService.promoteWaitingToProcessing();
        log.debug("[WaitingRoom-Queue] 스케줄러 실행 완료");
    }
}
