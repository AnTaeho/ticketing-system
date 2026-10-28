package com.example.ticketing.support;

import com.example.ticketing.booking.service.BookingReader;
import com.example.ticketing.booking.service.BookingService;
import com.example.ticketing.booking.service.BookingTransaction;
import com.example.ticketing.common.config.TicketingProperties;
import com.example.ticketing.common.redis.InstanceId;
import com.example.ticketing.common.redis.SchedulerLease;
import com.example.ticketing.common.redis.TicketingRedis;
import com.example.ticketing.common.time.DbClock;
import com.example.ticketing.hold.repository.SeatGateRedisStore;
import com.example.ticketing.hold.repository.SeatHoldJdbcRepository;
import com.example.ticketing.hold.repository.SeatHoldRepository;
import com.example.ticketing.hold.service.GateKeyReconcileScheduler;
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
import com.example.ticketing.payment.service.PaymentReader;
import com.example.ticketing.payment.service.PaymentReconcileScheduler;
import com.example.ticketing.payment.service.PaymentReconcileTransaction;
import com.example.ticketing.payment.service.PaymentService;
import com.example.ticketing.payment.service.PaymentTransaction;
import com.example.ticketing.queue.repository.QueueRedisStore;
import com.example.ticketing.queue.service.QueuePromotionScheduler;
import com.example.ticketing.queue.service.QueueService;
import com.example.ticketing.seat.repository.SeatJdbcRepository;
import com.example.ticketing.seat.repository.SeatMapRedisStore;
import com.example.ticketing.seat.repository.SeatRepository;
import com.example.ticketing.seat.service.SeatCatalog;
import com.example.ticketing.seat.service.SeatMapBuilder;
import com.example.ticketing.seat.service.SeatMapQueryService;
import com.example.ticketing.seat.service.SeatMapSnapshotScheduler;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.context.ApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
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

    public static SeatGateRedisStore gateStore(TicketingRedis redis) {
        return new SeatGateRedisStore(redis);
    }

    public static QueueRedisStore queueStore(TicketingRedis redis) {
        return new QueueRedisStore(redis);
    }

    public static SeatMapRedisStore seatMapStore(TicketingRedis redis) {
        return new SeatMapRedisStore(redis);
    }

    public SeatHoldServiceBuilder seatHoldService() {
        return new SeatHoldServiceBuilder();
    }

    public QueueServiceBuilder queueService() {
        return new QueueServiceBuilder();
    }

    public QueuePromotionScheduler queuePromotionScheduler(TicketingProperties properties, SeatCatalog catalog) {
        return new QueuePromotionScheduler(bean(SchedulerLease.class), bean(QueueRedisStore.class), catalog,
                bean(SeatMapQueryService.class), Clock.systemDefaultZone(), properties);
    }

    // 운영 카탈로그는 예매 중 회차 목록을 10초 묶어 두므로 방금 만든 회차가 빠질 수 있다. 캐시 없는 새 카탈로그다
    public SeatCatalog freshCatalog() {
        return new SeatCatalog(bean(SeatJdbcRepository.class), contextProperties(), System::nanoTime);
    }

    // 예매 중 회차 목록을 넘긴 값으로 고정한다
    public SeatCatalog catalogOf(List<Long> openIds) {
        return new SeatCatalog(bean(SeatJdbcRepository.class), contextProperties(), System::nanoTime) {
            @Override
            public List<Long> openPerformanceIds() {
                return openIds;
            }
        };
    }

    // DB로 고른 예매 중 회차 중에서 넘긴 회차만 남긴다. 다른 테스트가 만든 회차를 건드리지 않게 한다
    public SeatCatalog openCatalogAmong(Collection<Long> performanceIds) {
        return new SeatCatalog(bean(SeatJdbcRepository.class), contextProperties(), System::nanoTime) {
            @Override
            public List<Long> openPerformanceIds() {
                return super.openPerformanceIds().stream().filter(performanceIds::contains).toList();
            }
        };
    }

    public SchedulerLease lease(TicketingRedis redis) {
        return new SchedulerLease(redis, new InstanceId());
    }

    public SchedulerLease newLease() {
        return lease(bean(TicketingRedis.class));
    }

    public GateKeyReconcileSchedulerBuilder gateKeyReconcileScheduler() {
        return new GateKeyReconcileSchedulerBuilder();
    }

    public SeatMapQueryService seatMapQueryService(SeatMapRedisStore store, SeatMapBuilder builder) {
        return new SeatMapQueryService(bean(SeatCatalog.class), builder, store, bean(InstanceId.class),
                System::nanoTime, contextProperties(), meterRegistry());
    }

    // 동시 요청이 single-flight 안에서 겹치도록 빌드를 늦춘다
    public SeatMapBuilder slowSeatMapBuilder(long delayMs) {
        return new SeatMapBuilder(bean(SeatJdbcRepository.class), bean(JsonMapper.class), Clock.systemDefaultZone()) {
            @Override
            public Optional<String> build(long performanceId) {
                try {
                    Thread.sleep(delayMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return super.build(performanceId);
            }
        };
    }

    public SeatMapSnapshotScheduler seatMapSnapshotScheduler(InstanceId instanceId, SeatCatalog catalog) {
        return seatMapSnapshotScheduler(instanceId, catalog, bean(SeatMapRedisStore.class));
    }

    public SeatMapSnapshotScheduler seatMapSnapshotScheduler(InstanceId instanceId, SeatCatalog catalog,
                                                             SeatMapRedisStore store) {
        return new SeatMapSnapshotScheduler(catalog, bean(SeatMapBuilder.class), store, instanceId, System::nanoTime,
                contextProperties(), meterRegistry());
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
        return new BookingService(bean(BookingTransaction.class), bean(BookingReader.class), reflector);
    }

    public PaymentService paymentService(SeatGateRedisStore gateStore, TicketingProperties properties) {
        return new PaymentService(bean(PaymentTransaction.class), bean(PaymentReader.class), bean(PgClient.class),
                gateReflector(gateStore, properties), properties);
    }

    public static PaymentReconcileScheduler paymentReconcileScheduler(
            PaymentReconcileTransaction reconcileTransaction, PaymentTransaction paymentTransaction, PgClient pgClient,
            SeatGateReflector reflector, TicketingProperties properties, MeterRegistry meterRegistry) {
        return new PaymentReconcileScheduler(reconcileTransaction, paymentTransaction, pgClient, reflector, properties,
                meterRegistry);
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
        private QueueService queueService;
        private DbClock dbClock;

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

        public SeatHoldServiceBuilder queueService(QueueService queueService) {
            this.queueService = queueService;
            return this;
        }

        public SeatHoldServiceBuilder dbClock(DbClock dbClock) {
            this.dbClock = dbClock;
            return this;
        }

        // 반영 빈은 넘긴 게이트 저장소와 설정으로 새로 만든다
        public SeatHoldService build() {
            TicketingProperties resolved = properties != null ? properties : contextProperties();
            SeatGateRedisStore gate = gateStore != null ? gateStore : bean(SeatGateRedisStore.class);
            return new SeatHoldService(bean(SeatCatalog.class),
                    transaction != null ? transaction : bean(SeatHoldTransaction.class),
                    bean(SeatHoldRepository.class), bean(SeatRepository.class), bean(SeatHoldJdbcRepository.class),
                    dbClock != null ? dbClock : bean(DbClock.class), gate, gateReflector(gate, resolved),
                    queueService != null ? queueService : bean(QueueService.class),
                    new HoldMetrics(meterRegistry()), System::nanoTime, resolved);
        }
    }

    public final class QueueServiceBuilder {

        private TicketingProperties properties;
        private QueueRedisStore store;
        private SeatMapQueryService seatMap;
        private DbClock dbClock;

        public QueueServiceBuilder properties(TicketingProperties properties) {
            this.properties = properties;
            return this;
        }

        public QueueServiceBuilder store(QueueRedisStore store) {
            this.store = store;
            return this;
        }

        public QueueServiceBuilder seatMap(SeatMapQueryService seatMap) {
            this.seatMap = seatMap;
            return this;
        }

        public QueueServiceBuilder dbClock(DbClock dbClock) {
            this.dbClock = dbClock;
            return this;
        }

        public QueueService build() {
            return new QueueService(store != null ? store : bean(QueueRedisStore.class), bean(SeatCatalog.class),
                    seatMap != null ? seatMap : bean(SeatMapQueryService.class),
                    dbClock != null ? dbClock : bean(DbClock.class), Clock.systemDefaultZone(), System::nanoTime,
                    properties != null ? properties : contextProperties(), meterRegistry());
        }
    }

    public final class GateKeyReconcileSchedulerBuilder {

        private SchedulerLease lease;
        private SeatGateRedisStore gateStore;
        private NamedParameterJdbcTemplate jdbc;
        private SeatCatalog catalog;

        public GateKeyReconcileSchedulerBuilder lease(SchedulerLease lease) {
            this.lease = lease;
            return this;
        }

        public GateKeyReconcileSchedulerBuilder gateStore(SeatGateRedisStore gateStore) {
            this.gateStore = gateStore;
            return this;
        }

        // 대사의 DB 조회에 끼어들 때 쓴다
        public GateKeyReconcileSchedulerBuilder jdbc(NamedParameterJdbcTemplate jdbc) {
            this.jdbc = jdbc;
            return this;
        }

        public GateKeyReconcileSchedulerBuilder catalog(SeatCatalog catalog) {
            this.catalog = catalog;
            return this;
        }

        public GateKeyReconcileScheduler build() {
            return new GateKeyReconcileScheduler(lease != null ? lease : bean(SchedulerLease.class),
                    gateStore != null ? gateStore : bean(SeatGateRedisStore.class),
                    jdbc != null ? new SeatHoldJdbcRepository(jdbc) : bean(SeatHoldJdbcRepository.class),
                    catalog != null ? catalog : bean(SeatCatalog.class), System::nanoTime, contextProperties(),
                    meterRegistry());
        }
    }
}
