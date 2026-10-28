package com.example.ticketing.common.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.ticketing.support.IntegrationTest;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

@IntegrationTest
class TicketingPropertiesTest {

    @Autowired
    private TicketingProperties properties;

    @Test
    @DisplayName("설정(ticketing)이 Duration과 숫자로 묶이고 테스트에서는 시드와 스케줄러가 꺼진다")
    void bound() {
        assertThat(properties.seed().enabled()).isFalse();
        assertThat(properties.redis().commandTimeout()).isEqualTo(Duration.ofMillis(300));
        assertThat(properties.redis().connectTimeout()).isEqualTo(Duration.ofMillis(500));
        assertThat(properties.redis().database()).isEqualTo(1);
        assertThat(properties.hold().ttl()).isEqualTo(Duration.ofMinutes(5));
        assertThat(properties.hold().maxSeats()).isEqualTo(4);
        assertThat(properties.payment().payDeadline()).isEqualTo(Duration.ofMinutes(10));
        assertThat(properties.queue().waitingTokenTtl()).isEqualTo(Duration.ofMinutes(60));
        assertThat(properties.queue().waitingLeaveGrace()).isEqualTo(Duration.ofSeconds(10));
        assertThat(properties.queue().required()).isTrue();
        assertThat(properties.seatmap().leaseTtl()).isEqualTo(Duration.ofSeconds(2));
        assertThat(properties.outbox().lockDuration()).isEqualTo(Duration.ofSeconds(60));
        assertThat(properties.ops().orphanScanMs()).isEqualTo(30000);
        assertThat(properties.pg().baseUrl()).isEmpty();
        assertThat(properties.pg().readTimeout()).isEqualTo(Duration.ofSeconds(3));
        assertThat(properties.fakePg().declineRate()).isEqualTo(0.1);
        assertThat(properties.fakePg().slowDelay()).isEqualTo(Duration.ofSeconds(5));
        assertThat(properties.fakePg().delayMin()).isEqualTo(Duration.ofMillis(200));
        assertThat(properties.fakePg().staleMargin()).isEqualTo(Duration.ofSeconds(30));
        assertThat(properties.seatmap().localCache()).isEqualTo(Duration.ofSeconds(1));
        assertThat(properties.payment().gateKeyMargin()).isEqualTo(Duration.ofMinutes(1));
        assertThat(properties.payment().processingRecheckAfter()).isEqualTo(Duration.ofSeconds(5));
        assertThat(properties.payment().reconcileClaimLease()).isEqualTo(Duration.ofSeconds(30));
        assertThat(properties.outbox().maxBackoff()).isEqualTo(Duration.ofSeconds(60));
        assertThat(properties.ops().stalePaymentAfter()).isEqualTo(Duration.ofSeconds(30));
        assertThat(properties.hold().expiryEnabled()).isFalse();
        assertThat(properties.payment().reconcileEnabled()).isFalse();
        assertThat(properties.outbox().workerEnabled()).isFalse();
        assertThat(properties.queue().promoteEnabled()).isFalse();
        assertThat(properties.seatmap().snapshotEnabled()).isFalse();
        assertThat(properties.ops().orphanEnabled()).isFalse();
    }
}
