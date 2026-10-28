package com.example.ticketing.hold.service;

import com.example.ticketing.common.config.TicketingProperties;
import com.example.ticketing.hold.repository.SeatGateRedisStore;
import com.example.ticketing.hold.service.HoldExpiryTransaction.ExpiredHold;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

// 여러 서버가 동시에 돌아도 SKIP LOCKED로 나눠 집으므로 리스를 쓰지 않는다
@Slf4j
@Component
@ConditionalOnProperty(name = "booking.hold.expiry-enabled", havingValue = "true", matchIfMissing = true)
public class HoldExpiryScheduler {

    static final int MAX_ROUNDS = 10;

    private final HoldExpiryTransaction expiryTransaction;
    private final SeatGateRedisStore gate;
    private final boolean gateEnabled;
    private final int batchSize;
    private final Duration reholdCooldown;
    private final Counter expiredCounter;

    public HoldExpiryScheduler(HoldExpiryTransaction expiryTransaction, SeatGateRedisStore gate,
                               TicketingProperties properties, MeterRegistry meterRegistry) {
        this.expiryTransaction = expiryTransaction;
        this.gate = gate;
        this.gateEnabled = properties.gate().enabled();
        this.batchSize = properties.hold().expiryBatchSize();
        this.reholdCooldown = properties.hold().reholdCooldown();
        this.expiredCounter = meterRegistry.counter("booking.hold.expired");
    }

    @Scheduled(fixedDelayString = "${booking.hold.expiry-scan-ms}")
    public void scan() {
        int total = 0;
        for (int round = 0; round < MAX_ROUNDS; round++) {
            List<ExpiredHold> expired = expiryTransaction.claimAndExpire(batchSize);
            afterCommit(expired);
            total += expired.size();
            if (expired.size() < batchSize) {
                break;
            }
        }
        if (total > 0) {
            log.debug("[booking expiry] 만료 처리 {}건", total);
        }
    }

    // 커밋이 끝난 뒤라 Redis를 불러도 된다. 남이 가져간 좌석은 seatIds에서 빠져 쿨다운도 걸지 않는다
    private void afterCommit(List<ExpiredHold> expired) {
        expiredCounter.increment(expired.size());
        if (!gateEnabled) {
            return;
        }
        // 쿨다운을 먼저 걸어 키가 풀린 틈에 같은 사용자가 다시 잡지 못하게 한다
        for (ExpiredHold hold : expired) {
            if (!hold.seatIds().isEmpty()) {
                gate.setReholdCooldownQuietly(hold.performanceId(), hold.userId(), hold.seatIds(), reholdCooldown);
            }
            gate.releaseQuietly(hold.performanceId(), hold.userId(), hold.seatIds(), hold.holdToken());
        }
    }
}
