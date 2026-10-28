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
    private TicketingProperties props;

    @Test
    @DisplayName("booking 설정이 Duration과 숫자로 묶이고 테스트에서는 시드가 꺼진다")
    void bound() {
        assertThat(props.seed().enabled()).isFalse();
        assertThat(props.redis().commandTimeout()).isEqualTo(Duration.ofMillis(300));
        assertThat(props.redis().connectTimeout()).isEqualTo(Duration.ofMillis(500));
        assertThat(props.redis().database()).isEqualTo(1);
        assertThat(props.hold().ttl()).isEqualTo(Duration.ofMinutes(5));
        assertThat(props.hold().maxSeats()).isEqualTo(4);
        assertThat(props.payment().payDeadline()).isEqualTo(Duration.ofMinutes(10));
        assertThat(props.queue().waitingTokenTtl()).isEqualTo(Duration.ofMinutes(60));
        assertThat(props.queue().required()).isTrue();
        assertThat(props.seatmap().leaseTtl()).isEqualTo(Duration.ofSeconds(2));
        assertThat(props.outbox().lockDuration()).isEqualTo(Duration.ofSeconds(60));
        assertThat(props.ops().orphanScanMs()).isEqualTo(30000);
        assertThat(props.pg().baseUrl()).isEmpty();
        assertThat(props.pg().readTimeout()).isEqualTo(Duration.ofSeconds(3));
        assertThat(props.fakePg().declineRate()).isEqualTo(0.1);
        assertThat(props.fakePg().slowDelayMs()).isEqualTo(5000);
    }
}
