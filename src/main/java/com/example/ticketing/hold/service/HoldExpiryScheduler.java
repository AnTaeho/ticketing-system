package com.example.ticketing.hold.service;

import com.example.ticketing.common.config.TicketingProperties;
import com.example.ticketing.hold.dto.HoldExpiry;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

// 여러 서버가 동시에 돌아도 SKIP LOCKED로 나눠 집으므로 리스를 쓰지 않는다
@Slf4j
@Component
@ConditionalOnProperty(name = "ticketing.hold.expiry-enabled", havingValue = "true", matchIfMissing = true)
public class HoldExpiryScheduler {

    static final int MAX_ROUNDS = 10;

    private final HoldExpiryTransaction expiryTransaction;
    private final SeatGateReflector gateReflector;
    private final HoldMetrics metrics;
    private final int batchSize;

    public HoldExpiryScheduler(HoldExpiryTransaction expiryTransaction, SeatGateReflector gateReflector,
                               HoldMetrics metrics, TicketingProperties properties) {
        this.expiryTransaction = expiryTransaction;
        this.gateReflector = gateReflector;
        this.metrics = metrics;
        this.batchSize = properties.hold().expiryBatchSize();
    }

    @Scheduled(fixedDelayString = "${ticketing.hold.expiry-scan-ms}")
    public void run() {
        int total = 0;
        for (int round = 0; round < MAX_ROUNDS; round++) {
            List<HoldExpiry> expired = expiryTransaction.claimAndExpire(batchSize);
            afterCommit(expired);
            total += expired.size();
            if (expired.size() < batchSize) {
                break;
            }
        }
        if (total > 0) {
            log.debug("[expiry] 만료 처리 count={}", total);
        }
    }

    // 커밋이 끝난 뒤라 Redis를 불러도 된다
    private void afterCommit(List<HoldExpiry> expired) {
        metrics.expired(expired.size());
        expired.forEach(hold -> gateReflector.expire(hold.gate()));
    }
}
