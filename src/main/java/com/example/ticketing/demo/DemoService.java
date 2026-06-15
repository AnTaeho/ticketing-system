package com.example.ticketing.demo;

import com.example.ticketing.concert.service.ConcertService;
import com.example.ticketing.global.exception.LockAcquisitionFailedException;
import com.example.ticketing.global.exception.SoldOutException;
import com.example.ticketing.global.stock.RedisStockRepository;
import com.example.ticketing.queue.controller.dto.QueueStatusResponse;
import com.example.ticketing.queue.controller.dto.QueueTokenResponse;
import com.example.ticketing.queue.repository.QueueRedisRepository;
import com.example.ticketing.queue.service.QueueCommandService;
import com.example.ticketing.queue.service.QueueQueryService;
import com.example.ticketing.reservation.service.v7.TicketServiceV7;
import jakarta.annotation.PreDestroy;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import static com.example.ticketing.queue.QueueConst.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class DemoService {

    private static final int POLL_INTERVAL_MS  = 500;
    private static final int MAX_POLL_ATTEMPTS = 100;  // 50초 최대 대기
    private static final int SIMULATION_POOL_SIZE = 80;
    private static final int MAX_DEMO_USERS = 500;     // 데모 보호용 인원 상한
    private static final long SHUTDOWN_WAIT_SEC = 5;

    private final ExecutorService simulationPool = Executors.newFixedThreadPool(SIMULATION_POOL_SIZE);

    private final DemoStatsHolder    statsHolder;
    private final QueueCommandService queueCommandService;
    private final QueueQueryService  queueQueryService;
    private final TicketServiceV7    ticketServiceV7;
    private final ConcertService     concertService;
    private final QueueRedisRepository queueRepository;
    private final RedisStockRepository redisStockRepository;

    @Async
    public void start(Long concertId, int users) {
        users = Math.min(users, MAX_DEMO_USERS);
        queueRepository.clearQueues(concertId);
        statsHolder.reset(users);
        concertService.resetStock(concertId, 100);
        log.info("[Demo] 시작 - concertId={}, users={}", concertId, users);

        // Phase 1: 모든 사용자 토큰 일괄 발급 (대기열을 한 번에 채움)
        String[] tokens = issueAllTokens(concertId, users);

        // Phase 2: 스레드풀에서 대기 → 선점 처리
        for (int i = 0; i < users; i++) {
            // 토큰 발급 실패자는 시뮬레이션 대상이 아니므로 즉시 실패로 종결한다.
            // (terminal 카운트를 남기지 않으면 done < total 로 고정돼 SSE가 종료되지 않음)
            if (tokens[i] == null) {
                statsHolder.incrementFailed();
                continue;
            }
            final String token  = tokens[i];
            final long   userId = (long) (i + 1);
            simulationPool.submit(() -> waitAndReserve(concertId, userId, token));
        }
    }

    public DemoStats buildStats(Long concertId) {
        int processingCount = queueRepository.getProcessingCount(concertId);
        int waitingCount    = queueRepository.getWaitingCount(concertId);
        long stock          = redisStockRepository.getStock(concertId);
        return statsHolder.snapshot(processingCount, waitingCount, stock);
    }

    @PreDestroy
    public void shutdown() {
        simulationPool.shutdown();
        try {
            if (!simulationPool.awaitTermination(SHUTDOWN_WAIT_SEC, TimeUnit.SECONDS)) {
                simulationPool.shutdownNow();
            }
        } catch (InterruptedException e) {
            simulationPool.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    private String[] issueAllTokens(Long concertId, int users) {
        String[] tokens = new String[users];
        for (int i = 0; i < users; i++) {
            try {
                QueueTokenResponse resp = queueCommandService.issueTokenAndEnqueue((long)(i + 1), concertId);
                tokens[i] = resp.token();
                statsHolder.incrementEnqueued();
            } catch (Exception e) {
                log.warn("[Demo] 토큰 발급 실패 - userId={}", i + 1);
            }
        }
        log.info("[Demo] 토큰 발급 완료 - 처리열={}, 대기열={}",
                queueRepository.getProcessingCount(concertId),
                queueRepository.getWaitingCount(concertId));
        return tokens;
    }

    private void waitAndReserve(Long concertId, Long userId, String token) {
        try {
            // 처리열 입장 실패(이탈/타임아웃/큐 이탈)는 모두 실패로 종결한다.
            if (!awaitProcessing(concertId, token)) {
                statsHolder.incrementFailed();
                return;
            }

            if (Math.random() < 0.5) {
                queueCommandService.removeFromProcessing(concertId, token);
                statsHolder.incrementAbandoned();
                return;
            }

            ticketServiceV7.reserve(concertId, userId, token);
            statsHolder.incrementSuccess();

        } catch (SoldOutException e) {
            statsHolder.incrementSoldOut();
        } catch (LockAcquisitionFailedException e) {
            statsHolder.incrementFailed();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            statsHolder.incrementFailed();
        } catch (Throwable e) {
            // Error 포함 모든 예외를 종결 처리해야 done == total 이 보장돼 SSE가 닫힌다.
            log.warn("[Demo] 예약 중 오류 - userId={}: {}", userId, e.getMessage());
            statsHolder.incrementFailed();
        }
    }

    /**
     * 토큰이 처리열에 입장할 때까지 폴링한다.
     *
     * @return true=처리열 입장, false=큐에서 이탈했거나 최대 대기 시간 초과
     */
    private boolean awaitProcessing(Long concertId, String token) throws InterruptedException {
        for (int polls = 0; polls < MAX_POLL_ATTEMPTS; polls++) {
            QueueStatusResponse status = queueQueryService.getQueueStatus(concertId, token);

            if (STATUS_PROCESSING.equals(status.status())) return true;
            if (STATUS_NOT_IN_QUEUE.equals(status.status())) return false;

            Thread.sleep(POLL_INTERVAL_MS);
        }
        return false;
    }
}
