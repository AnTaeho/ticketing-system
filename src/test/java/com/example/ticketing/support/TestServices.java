package com.example.ticketing.support;

import com.example.ticketing.booking.service.BookingService;
import com.example.ticketing.booking.service.BookingTransaction;
import com.example.ticketing.common.config.TicketingProperties;
import com.example.ticketing.common.time.DbClock;
import com.example.ticketing.hold.repository.SeatGateRedisStore;
import com.example.ticketing.hold.repository.SeatHoldRepository;
import com.example.ticketing.hold.service.HoldExpiryScheduler;
import com.example.ticketing.hold.service.HoldExpiryTransaction;
import com.example.ticketing.hold.service.HoldMetrics;
import com.example.ticketing.hold.service.SeatGateReflector;
import com.example.ticketing.hold.service.SeatHoldService;
import com.example.ticketing.hold.service.SeatHoldTransaction;
import com.example.ticketing.outbox.service.OutboxDispatchScheduler;
import com.example.ticketing.outbox.service.OutboxHandler;
import com.example.ticketing.outbox.service.OutboxTaskTransaction;
import com.example.ticketing.payment.client.HttpPgClient;
import com.example.ticketing.payment.client.PgClient;
import com.example.ticketing.payment.service.PaymentGateReflector;
import com.example.ticketing.payment.service.PaymentReconcileScheduler;
import com.example.ticketing.payment.service.PaymentReconcileTransaction;
import com.example.ticketing.payment.service.PaymentTransaction;
import com.example.ticketing.seat.repository.SeatRepository;
import com.example.ticketing.seat.service.SeatCatalog;
import com.example.ticketing.seat.service.SeatMapBuilder;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.context.ApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.core.StringRedisTemplate;
import tools.jackson.databind.json.JsonMapper;

// 테스트가 운영 서비스를 new로 조립하는 곳. 컨텍스트 빈을 기본으로 쓰고 바꿀 것만 끼운다. 생성자가 바뀌면 여기만 고친다
@TestComponent
public class TestServices {

    private final ApplicationContext context;

    public TestServices(ApplicationContext context) {
        this.context = context;
    }

    private <T> T bean(Class<T> type) {
        return context.getBean(type);
    }

    private TicketingProperties contextProperties() {
        return bean(TicketingProperties.class);
    }

    private MeterRegistry meterRegistry() {
        return bean(MeterRegistry.class);
    }

    // 컨텍스트 없는 테스트도 쓴다
    public static SeatGateReflector gateReflector(SeatGateRedisStore store, TicketingProperties properties,
                                                  MeterRegistry meterRegistry) {
        return new SeatGateReflector(store, properties, meterRegistry, System::nanoTime);
    }

    public SeatGateReflector gateReflector(SeatGateRedisStore store, TicketingProperties properties) {
        return gateReflector(store, properties, meterRegistry());
    }

    public static SeatGateRedisStore gateStore(StringRedisTemplate redis) {
        return new SeatGateRedisStore(redis);
    }

    public SeatHoldServiceBuilder seatHoldService() {
        return new SeatHoldServiceBuilder();
    }

    public static HoldExpiryScheduler holdExpiryScheduler(HoldExpiryTransaction expiryTransaction,
                                                          SeatGateReflector reflector, MeterRegistry meterRegistry,
                                                          TicketingProperties properties) {
        return new HoldExpiryScheduler(expiryTransaction, reflector, new HoldMetrics(meterRegistry), properties);
    }

    public HoldExpiryScheduler holdExpiryScheduler() {
        return holdExpiryScheduler(bean(HoldExpiryTransaction.class), bean(SeatGateReflector.class), meterRegistry(),
                contextProperties());
    }

    public BookingService bookingService(SeatGateReflector reflector) {
        return new BookingService(bean(BookingTransaction.class), reflector);
    }

    public static PaymentReconcileScheduler paymentReconcileScheduler(
            PaymentReconcileTransaction reconcileTransaction, PaymentTransaction paymentTransaction, PgClient pgClient,
            SeatGateReflector reflector, TicketingProperties properties, MeterRegistry meterRegistry) {
        return new PaymentReconcileScheduler(reconcileTransaction, paymentTransaction, pgClient,
                new PaymentGateReflector(reflector), properties, meterRegistry);
    }

    // 컨텍스트의 PG 클라이언트(대역이 있으면 대역)로 만든다
    public PaymentReconcileScheduler paymentReconcileScheduler() {
        return paymentReconcileScheduler(bean(PaymentReconcileTransaction.class), bean(PaymentTransaction.class),
                bean(PgClient.class), bean(SeatGateReflector.class), contextProperties(), meterRegistry());
    }

    public OutboxDispatchScheduler outboxDispatchScheduler() {
        List<OutboxHandler<?>> handlers = context.getBeanProvider(OutboxHandler.class).orderedStream()
                .<OutboxHandler<?>>map(handler -> handler)
                .toList();
        return outboxDispatchScheduler(handlers, contextProperties());
    }

    public OutboxDispatchScheduler outboxDispatchScheduler(List<OutboxHandler<?>> handlers,
                                                           TicketingProperties properties) {
        return new OutboxDispatchScheduler(bean(OutboxTaskTransaction.class), handlers, bean(JsonMapper.class),
                System::nanoTime, properties, meterRegistry());
    }

    public HttpPgClient httpPgClient(TicketingProperties properties, Environment environment) {
        return new HttpPgClient(properties, environment, bean(JsonMapper.class), System::nanoTime);
    }

    public final class SeatHoldServiceBuilder {

        private TicketingProperties properties;
        private SeatGateRedisStore gateStore;
        private SeatHoldTransaction transaction;

        public SeatHoldServiceBuilder properties(TicketingProperties properties) {
            this.properties = properties;
            return this;
        }

        public SeatHoldServiceBuilder gateStore(SeatGateRedisStore gateStore) {
            this.gateStore = gateStore;
            return this;
        }

        public SeatHoldServiceBuilder transaction(SeatHoldTransaction transaction) {
            this.transaction = transaction;
            return this;
        }

        // 반영 빈은 넘긴 게이트 저장소와 설정으로 새로 만든다
        public SeatHoldService build() {
            TicketingProperties resolved = properties != null ? properties : contextProperties();
            SeatGateRedisStore gate = gateStore != null ? gateStore : bean(SeatGateRedisStore.class);
            return new SeatHoldService(bean(SeatCatalog.class),
                    transaction != null ? transaction : bean(SeatHoldTransaction.class),
                    bean(SeatHoldRepository.class),
                    bean(DbClock.class), Clock.systemDefaultZone(), gate, gateReflector(gate, resolved),
                    new HoldMetrics(meterRegistry()), System::nanoTime, resolved);
        }
    }
}
