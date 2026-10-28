package com.example.ticketing.common.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

// *Enabled는 @ConditionalOnProperty가 같은 키를 읽는다. 키를 한곳에서 보이려고 접근자를 안 불러도 여기에 둔다
@ConfigurationProperties("ticketing")
public record TicketingProperties(
        Seed seed,
        Redis redis,
        Hold hold,
        Payment payment,
        Gate gate,
        Queue queue,
        Seatmap seatmap,
        Outbox outbox,
        Ops ops,
        Pg pg,
        FakePg fakePg
) {

    public record Seed(boolean enabled) {
    }

    public record Redis(Duration commandTimeout, Duration connectTimeout, int database) {
    }

    public record Hold(
            Duration ttl,
            int maxSeats,
            boolean expiryEnabled,
            long expiryScanMs, // @Scheduled 전용
            int expiryBatchSize,
            Duration reholdCooldown
    ) {
    }

    public record Payment(
            Duration payDeadline,
            Duration gateKeyMargin,
            Duration requestedRecheckAfter,
            Duration processingRecheckAfter,
            Duration unknownRecheckAfter,
            Duration notFoundCancelAfter,
            boolean reconcileEnabled,
            long reconcileScanMs, // @Scheduled 전용
            int reconcileBatchSize,
            Duration reconcileClaimLease
    ) {
    }

    public record Gate(boolean enabled, int fallbackMaxConcurrency) {
    }

    public record Queue(
            int capacity,
            Duration processingTokenTtl,
            Duration waitingTokenTtl,
            boolean promoteEnabled,
            long promoteIntervalMs,
            boolean required
    ) {
    }

    public record Seatmap(
            boolean snapshotEnabled,
            long snapshotIntervalMs, // @Scheduled 전용
            Duration snapshotTtl,
            Duration localCache,
            Duration leaseTtl
    ) {
    }

    public record Outbox(
            boolean workerEnabled,
            long pollIntervalMs, // @Scheduled 전용
            int batchSize,
            Duration lockDuration,
            int maxAttempts,
            Duration maxBackoff
    ) {
    }

    public record Ops(boolean orphanEnabled, long orphanScanMs, Duration stalePaymentAfter) {
    }

    public record Pg(String baseUrl, Duration connectTimeout, Duration readTimeout) {
    }

    public record FakePg(
            Duration delayMin,
            Duration delayMax,
            double declineRate,
            double slowRate,
            Duration slowDelay,
            Duration staleMargin
    ) {
    }
}
