package com.example.ticketing.booking;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("booking")
public record BookingProperties(
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
            long expiryScanMs,
            int expiryBatchSize,
            Duration reholdCooldown
    ) {
    }

    public record Payment(
            Duration payDeadline,
            Duration requestedRecheckAfter,
            Duration unknownRecheckAfter,
            Duration notFoundCancelAfter,
            long reconcileScanMs,
            int reconcileBatchSize
    ) {
    }

    public record Gate(boolean enabled, int fallbackMaxConcurrency) {
    }

    public record Queue(
            int capacity,
            Duration processingTokenTtl,
            Duration waitingTokenTtl,
            long promoteIntervalMs,
            boolean required
    ) {
    }

    public record Seatmap(
            long snapshotIntervalMs,
            Duration snapshotTtl,
            long localCacheMs,
            Duration leaseTtl
    ) {
    }

    public record Outbox(
            long pollIntervalMs,
            int batchSize,
            Duration lockDuration,
            int maxAttempts
    ) {
    }

    public record Ops(long orphanScanMs) {
    }

    public record Pg(String baseUrl, Duration connectTimeout, Duration readTimeout) {
    }

    public record FakePg(
            long delayMinMs,
            long delayMaxMs,
            double declineRate,
            double slowRate,
            long slowDelayMs
    ) {
    }
}
