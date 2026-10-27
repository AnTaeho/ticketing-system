package com.example.ticketing.booking.hold;

import com.example.ticketing.booking.BookingProperties;
import com.example.ticketing.booking.hold.HoldExpiryTransaction.ExpiredHold;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
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
    private final int batchSize;
    private final Counter expiredCounter;

    public HoldExpiryScheduler(HoldExpiryTransaction expiryTransaction, BookingProperties properties,
                               MeterRegistry meterRegistry) {
        this.expiryTransaction = expiryTransaction;
        this.batchSize = properties.hold().expiryBatchSize();
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

    // 커밋이 끝난 뒤라 Redis를 불러도 된다. 게이트 해제와 재선점 쿨다운(S3.2)이 여기에 붙는다
    private void afterCommit(List<ExpiredHold> expired) {
        expiredCounter.increment(expired.size());
    }
}
