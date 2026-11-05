package com.example.ticketing.support;

import com.example.ticketing.booking.service.BookingIssuer;
import com.example.ticketing.booking.service.BookingReader;
import com.example.ticketing.booking.service.BookingService;
import com.example.ticketing.booking.service.PaymentRefunder;
import com.example.ticketing.booking.service.BookingTransaction;
import com.example.ticketing.common.config.TicketingProperties;
import com.example.ticketing.common.time.DbClock;
import com.example.ticketing.hold.repository.SeatGateRedisStore;
import com.example.ticketing.hold.repository.SeatHoldRepository;
import com.example.ticketing.hold.service.HoldExpiryScheduler;
import com.example.ticketing.hold.service.HoldExpiryTransaction;
import com.example.ticketing.hold.service.SeatGateReflector;
import com.example.ticketing.hold.service.SeatHoldService;
import com.example.ticketing.hold.service.SeatHoldTransaction;
import com.example.ticketing.payment.client.HttpPgClient;
import com.example.ticketing.payment.client.PgClient;
import com.example.ticketing.payment.repository.PaymentRepository;
import com.example.ticketing.payment.service.PaymentReader;
import com.example.ticketing.payment.service.PaymentReconcileScheduler;
import com.example.ticketing.payment.service.PaymentTransaction;
import com.example.ticketing.seat.repository.SeatRepository;
import com.example.ticketing.seat.service.SeatCatalog;
import java.time.Clock;
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

    // 컨텍스트 없는 테스트도 쓴다
    public static SeatGateReflector gateReflector(SeatGateRedisStore store, TicketingProperties properties) {
        return new SeatGateReflector(store, properties);
    }

    public static SeatGateRedisStore gateStore(StringRedisTemplate redis) {
        return new SeatGateRedisStore(redis);
    }

    public SeatHoldServiceBuilder seatHoldService() {
        return new SeatHoldServiceBuilder();
    }

    public static HoldExpiryScheduler holdExpiryScheduler(HoldExpiryTransaction expiryTransaction,
                                                          SeatGateReflector reflector,
                                                          TicketingProperties properties) {
        return new HoldExpiryScheduler(expiryTransaction, reflector, properties);
    }

    public HoldExpiryScheduler holdExpiryScheduler() {
        return holdExpiryScheduler(bean(HoldExpiryTransaction.class), bean(SeatGateReflector.class),
                contextProperties());
    }

    // DB 시계를 바꿔 끼운 결제 트랜잭션. 판정 시각이 DbClock에서 오는지 볼 때 쓴다
    public PaymentTransaction paymentTransaction(DbClock dbClock) {
        return new PaymentTransaction(bean(PaymentRepository.class), bean(PaymentReader.class),
                bean(SeatHoldRepository.class), bean(SeatRepository.class), bean(BookingIssuer.class), dbClock,
                contextProperties());
    }

    public BookingService bookingService(SeatGateReflector reflector) {
        return bookingService(reflector, bean(BookingTransaction.class));
    }

    public BookingService bookingService(SeatGateReflector reflector, BookingTransaction transaction) {
        return new BookingService(bean(BookingReader.class), bean(PaymentRefunder.class), transaction, reflector);
    }

    public static PaymentReconcileScheduler paymentReconcileScheduler(
            PaymentReader paymentReader,
            PaymentTransaction paymentTransaction, PgClient pgClient,
            SeatGateReflector reflector, TicketingProperties properties) {
        return new PaymentReconcileScheduler(paymentReader, paymentTransaction, pgClient,
                reflector, properties);
    }

    // 컨텍스트의 PG 클라이언트(대역이 있으면 대역)로 만든다
    public PaymentReconcileScheduler paymentReconcileScheduler() {
        return paymentReconcileScheduler(bean(PaymentReader.class), bean(PaymentTransaction.class),
                bean(PgClient.class), bean(SeatGateReflector.class), contextProperties());
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
                    resolved);
        }
    }
}
