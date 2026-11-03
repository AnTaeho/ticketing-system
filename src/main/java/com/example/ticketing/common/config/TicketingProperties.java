package com.example.ticketing.common.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

// *Enabled는 @ConditionalOnProperty가 같은 키를 읽는다. 키를 한곳에서 보이려고 접근자를 안 불러도 여기에 둔다
@ConfigurationProperties("ticketing")
public record TicketingProperties(
        Seed seed,
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

    public record Gate(
            boolean enabled,
            int fallbackMaxConcurrency,
            boolean reconcileEnabled,
            long reconcileScanMs // @Scheduled 전용
    ) {
    }

    public record Queue(
            int capacity,
            Duration processingMaxSession,
            Duration processingIdleTimeout,
            Duration processingLeaveGrace,
            Duration waitingLeaveGrace,
            Duration waitingTokenTtl,
            boolean promoteEnabled,
            long promoteIntervalMs
    ) {

        // 입장 직후 처리열 자리가 남는 시간. 생존 신호가 오기 전까지다
        public Duration admissionWindow() {
            return processingIdleTimeout.compareTo(processingMaxSession) < 0 ? processingIdleTimeout
                    : processingMaxSession;
        }
    }

    // 로컬 캐시와 스케줄러 리스 길이는 주기에서 정한다
    public record Seatmap(
            boolean snapshotEnabled,
            long snapshotIntervalMs
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

    public record Ops(Duration stalePaymentAfter) {
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
