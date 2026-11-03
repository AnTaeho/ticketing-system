package com.example.ticketing.common.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.ticketing.hold.service.GateKeyReconcileScheduler;
import com.example.ticketing.hold.service.HoldExpiryScheduler;
import com.example.ticketing.hold.service.HoldExpiryTransaction;
import com.example.ticketing.outbox.service.OutboxDispatchScheduler;
import com.example.ticketing.payment.service.PaymentReconcileScheduler;
import com.example.ticketing.queue.service.QueuePromotionScheduler;
import com.example.ticketing.seat.service.SeatMapSnapshotScheduler;
import com.example.ticketing.support.IntegrationTest;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.validation.BindValidationException;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ApplicationContext;

@IntegrationTest
class TicketingPropertiesTest {

    @Autowired
    private TicketingProperties properties;
    @Autowired
    private ApplicationContext context;

    @Test
    @DisplayName("설정(ticketing)이 Duration과 숫자로 묶이고 테스트에서는 시드와 스케줄러가 꺼진다")
    void bound() {
        assertThat(properties.seed().enabled()).isFalse();
        assertThat(properties.hold().ttl()).isEqualTo(Duration.ofMinutes(5));
        assertThat(properties.hold().maxSeats()).isEqualTo(4);
        assertThat(properties.payment().payDeadline()).isEqualTo(Duration.ofMinutes(10));
        assertThat(properties.queue().waitingTokenTtl()).isEqualTo(Duration.ofMinutes(60));
        assertThat(properties.queue().waitingLeaveGrace()).isEqualTo(Duration.ofSeconds(10));
        assertThat(properties.seatmap().snapshotIntervalMs()).isEqualTo(1000);
        assertThat(properties.outbox().lockDuration()).isEqualTo(Duration.ofSeconds(60));
        assertThat(properties.gate().reconcileScanMs()).isEqualTo(30000);
        assertThat(properties.pg().baseUrl()).isEmpty();
        assertThat(properties.pg().readTimeout()).isEqualTo(Duration.ofSeconds(3));
        assertThat(properties.fakePg().declineRate()).isEqualTo(0.1);
        assertThat(properties.fakePg().slowDelay()).isEqualTo(Duration.ofSeconds(5));
        assertThat(properties.fakePg().delayMin()).isEqualTo(Duration.ofMillis(200));
        assertThat(properties.payment().gateKeyMargin()).isEqualTo(Duration.ofMinutes(1));
        assertThat(properties.payment().processingRecheckAfter()).isEqualTo(Duration.ofSeconds(5));
        assertThat(properties.payment().reconcileClaimLease()).isEqualTo(Duration.ofSeconds(30));
        assertThat(properties.outbox().maxBackoff()).isEqualTo(Duration.ofSeconds(60));
        assertThat(properties.hold().expiryEnabled()).isFalse();
        assertThat(properties.payment().reconcileEnabled()).isFalse();
        assertThat(properties.outbox().workerEnabled()).isFalse();
        assertThat(properties.queue().promoteEnabled()).isFalse();
        assertThat(properties.seatmap().snapshotEnabled()).isFalse();
        assertThat(properties.gate().reconcileEnabled()).isFalse();
    }

    @Test
    @DisplayName("스케줄러 스위치(*-enabled)가 꺼진 테스트 컨텍스트에는 스케줄러 빈이 없고 위임 대상 빈은 있다")
    void schedulerBeansDisabledInTests() {
        List.of(HoldExpiryScheduler.class, GateKeyReconcileScheduler.class, PaymentReconcileScheduler.class,
                        OutboxDispatchScheduler.class, QueuePromotionScheduler.class, SeatMapSnapshotScheduler.class)
                .forEach(type -> assertThat(context.getBeanProvider(type).getIfAvailable()).as(type.getSimpleName())
                        .isNull());
        assertThat(context.getBeanProvider(HoldExpiryTransaction.class).getIfAvailable()).isNotNull();
    }

    @Test
    @DisplayName("설정 값이 범위를 벗어나면(배치 0, 음수 기간, 1을 넘는 비율) 기동이 실패한다")
    void invalidValuesFailStartup() {
        ApplicationContextRunner runner = new ApplicationContextRunner()
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withUserConfiguration(PropertiesOnly.class);

        runner.run(context -> assertThat(context).hasNotFailed().hasSingleBean(TicketingProperties.class));
        List.of("ticketing.outbox.batch-size=0", "ticketing.hold.ttl=-1s", "ticketing.fake-pg.decline-rate=1.5")
                .forEach(invalid -> runner.withPropertyValues(invalid)
                        .run(context -> assertThat(context.getStartupFailure()).as(invalid)
                                .hasRootCauseInstanceOf(BindValidationException.class)));
    }

    // @Configuration을 달면 이 테스트 클래스의 컨텍스트 설정으로 잡히므로 달지 않는다
    @EnableConfigurationProperties(TicketingProperties.class)
    static class PropertiesOnly {
    }
}
