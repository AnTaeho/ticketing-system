package com.example.ticketing.common.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.time.Duration;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

// *Enabled는 @ConditionalOnProperty가 같은 키를 읽는다. 키를 한곳에서 보이려고 접근자를 안 불러도 여기에 둔다
// 값이 빠지거나 범위를 벗어나면 기동에서 막는다
@Validated
@ConfigurationProperties("ticketing")
public record TicketingProperties(
        @NotNull @Valid Seed seed,
        @NotNull @Valid Hold hold,
        @NotNull @Valid Payment payment,
        @NotNull @Valid Gate gate,
        @NotNull @Valid Pg pg,
        @NotNull @Valid FakePg fakePg
) {

    public record Seed(boolean enabled) {
    }

    public record Hold(
            @NotNull @DurationMin(millis = 1) Duration ttl,
            @Positive int maxSeats,
            boolean expiryEnabled,
            @Positive long expiryScanMs, // @Scheduled 전용
            @Positive int expiryBatchSize
    ) {
    }

    public record Payment(
            @NotNull @DurationMin Duration requestedRecheckAfter,
            @NotNull @DurationMin Duration processingRecheckAfter,
            @NotNull @DurationMin Duration unknownRecheckAfter,
            @NotNull @DurationMin Duration notFoundCancelAfter,
            boolean reconcileEnabled,
            @Positive long reconcileScanMs, // @Scheduled 전용
            @Positive int reconcileBatchSize
    ) {
    }

    public record Gate(
            boolean enabled
    ) {
    }

    public record Pg(
            @NotNull String baseUrl,
            @NotNull @DurationMin(millis = 1) Duration connectTimeout,
            @NotNull @DurationMin(millis = 1) Duration readTimeout
    ) {
    }

    public record FakePg(
            @NotNull @DurationMin Duration delayMin,
            @NotNull @DurationMin Duration delayMax,
            @DecimalMin("0") @DecimalMax("1") double declineRate,
            @DecimalMin("0") @DecimalMax("1") double slowRate,
            @NotNull @DurationMin Duration slowDelay
    ) {
    }
}
