package com.example.ticketing.booking.hold;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.example.ticketing.booking.BookingProperties;
import com.example.ticketing.booking.BookingProperties.Gate;
import com.example.ticketing.booking.BookingProperties.Queue;
import com.example.ticketing.booking.common.BookingErrorCode;
import com.example.ticketing.booking.common.BookingException;
import com.example.ticketing.booking.common.BookingRedis;
import com.example.ticketing.booking.common.DbClock;
import com.example.ticketing.booking.common.TestBookingRedis;
import com.example.ticketing.booking.hold.dto.HoldCreated;
import com.example.ticketing.booking.hold.gate.BookingKeys;
import com.example.ticketing.booking.hold.gate.RedisSeatGate;
import com.example.ticketing.booking.payment.BookingPaymentRepository;
import com.example.ticketing.booking.queue.BookingQueueRepository;
import com.example.ticketing.booking.queue.BookingQueueService;
import com.example.ticketing.booking.queue.QueueKeys;
import com.example.ticketing.booking.seat.SeatCatalog;
import com.example.ticketing.booking.seat.SeatRepository;
import com.example.ticketing.booking.support.BookingFixtures;
import com.example.ticketing.booking.support.BookingIntegrationTest;
import com.example.ticketing.booking.support.RedisKeyCleaner;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.IOException;
import java.net.ServerSocket;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

@BookingIntegrationTest
class SeatHoldQueueTest {

    @Autowired
    private SeatHoldService seatHoldService;
    @Autowired
    private BookingQueueService queueService;
    @Autowired
    private BookingFixtures fixtures;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private MeterRegistry meterRegistry;
    @Autowired
    private BookingRedis bookingRedis;
    @Autowired
    private RedisKeyCleaner redisKeyCleaner;
    @Autowired
    private BookingProperties properties;
    @Autowired
    private SeatCatalog seatCatalog;
    @Autowired
    private SeatHoldTransaction seatHoldTransaction;
    @Autowired
    private SeatHoldRepository seatHoldRepository;
    @Autowired
    private SeatRepository seatRepository;
    @Autowired
    private BookingPaymentRepository paymentRepository;
    @Autowired
    private DbClock dbClock;
    @Autowired
    private RedisSeatGate gate;

    private final List<Long> performances = new ArrayList<>();

    @AfterEach
    void tearDown() {
        performances.forEach(redisKeyCleaner::clean);
    }

    @Test
    @DisplayName("토큰 없음·빈 토큰·남의 토큰·없는 토큰·점수가 지난 토큰은 403 NOT_ADMITTED이고 게이트·DB에 닿지 않는다")
    void notAdmittedStopsBeforeGateAndDb() {
        long pid = newPerformance();
        List<Long> target = seatIds(pid).subList(0, 1);
        long userA = fixtures.newUser();
        long userB = fixtures.newUser();
        String tokenB = fixtures.admit(pid, userB);
        String expiredA = fixtures.admit(pid, userA);
        redis().opsForZSet().add(QueueKeys.processing(pid), expiredA, System.currentTimeMillis() - 1_000);
        double gateBefore = counterSum("booking.hold.gate");
        double dbBefore = counterSum("booking.hold.db");

        for (String token : new String[]{null, "", "  ", tokenB, userA + "." + UUID.randomUUID(), expiredA}) {
            assertThatThrownBy(() -> seatHoldService.hold(userA, pid, target, token))
                    .as("token=%s", token)
                    .isInstanceOf(BookingException.class)
                    .extracting("code").isEqualTo(BookingErrorCode.NOT_ADMITTED);
        }

        assertThat(counterSum("booking.hold.gate")).isEqualTo(gateBefore);
        assertThat(counterSum("booking.hold.db")).isEqualTo(dbBefore);
        assertThat(holdCount(pid)).isZero();
        assertThat(redisKeyCleaner.gateKeys(pid)).isEmpty();
        // 남의 토큰을 써 봐도 그 사람의 자리는 그대로다
        assertThat(processingScore(pid, tokenB)).isNotNull();
    }

    @Test
    @DisplayName("게이트 409(SEAT_TAKEN) 뒤에도 토큰이 처리열에 남아 같은 토큰으로 다른 좌석을 잡고, 그때 소모된다")
    void gateRejectionKeepsToken() {
        long pid = newPerformance();
        List<Long> ids = seatIds(pid);
        long userA = fixtures.newUser();
        long userB = fixtures.newUser();
        seatHoldService.hold(userA, pid, List.of(ids.get(0)), fixtures.admit(pid, userA));
        String tokenB = fixtures.admit(pid, userB);

        assertThatThrownBy(() -> seatHoldService.hold(userB, pid, List.of(ids.get(0)), tokenB))
                .extracting("code").isEqualTo(BookingErrorCode.SEAT_TAKEN);
        assertThat(processingScore(pid, tokenB)).isNotNull();
        assertThat(redis().opsForValue().get(QueueKeys.user(pid, userB))).isEqualTo(tokenB);

        HoldCreated holdB = seatHoldService.hold(userB, pid, List.of(ids.get(1)), tokenB);

        assertThat(holdB.seats()).extracting(s -> s.id()).containsExactly(ids.get(1));
        assertThat(processingScore(pid, tokenB)).isNull();
        assertThat(redis().hasKey(QueueKeys.user(pid, userB))).isFalse();
    }

    @Test
    @DisplayName("DB 판정 409(SEAT_STATE_CHANGED)도 토큰을 소모하지 않는다")
    void dbRejectionKeepsToken() {
        long pid = newPerformance();
        List<Long> target = seatIds(pid).subList(0, 1);
        long userA = fixtures.newUser();
        long userB = fixtures.newUser();
        seatHoldService.hold(userA, pid, target, fixtures.admit(pid, userA));
        redis().delete(BookingKeys.seat(pid, target.get(0)));
        String tokenB = fixtures.admit(pid, userB);

        assertThatThrownBy(() -> seatHoldService.hold(userB, pid, target, tokenB))
                .extracting("code").isEqualTo(BookingErrorCode.SEAT_STATE_CHANGED);

        assertThat(processingScore(pid, tokenB)).isNotNull();
        assertThat(redis().opsForValue().get(QueueKeys.user(pid, userB))).isEqualTo(tokenB);
    }

    @Test
    @DisplayName("성공하면 토큰이 처리열과 사용자 키에서 빠지고, 선점을 취소한 뒤 같은 토큰으로 다시 잡으면 403")
    void successConsumesToken() {
        long pid = newPerformance();
        List<Long> target = seatIds(pid).subList(0, 2);
        long userId = fixtures.newUser();
        String token = fixtures.admit(pid, userId);

        HoldCreated hold = seatHoldService.hold(userId, pid, target, token);

        assertThat(processingScore(pid, token)).isNull();
        assertThat(redis().hasKey(QueueKeys.user(pid, userId))).isFalse();

        seatHoldService.release(userId, hold.holdId());
        double gateBefore = counterSum("booking.hold.gate");
        double dbBefore = counterSum("booking.hold.db");
        assertThatThrownBy(() -> seatHoldService.hold(userId, pid, target, token))
                .extracting("code").isEqualTo(BookingErrorCode.NOT_ADMITTED);
        assertThat(counterSum("booking.hold.gate")).isEqualTo(gateBefore);
        assertThat(counterSum("booking.hold.db")).isEqualTo(dbBefore);
        assertThat(holdCount(pid)).isEqualTo(1);
    }

    @Test
    @DisplayName("토큰 소모가 Redis 예외로 실패해도 선점은 201이고 queue_consume 반영 실패 카운터만 늘어난다")
    void consumeFailureIsSwallowed() {
        long pid = newPerformance();
        long userId = fixtures.newUser();
        String token = userId + "." + UUID.randomUUID();
        BookingQueueService queue = mock(BookingQueueService.class);
        when(queue.isAdmitted(pid, token, userId)).thenReturn(true);
        when(queue.consume(pid, token, userId)).thenThrow(new QueryTimeoutException("consume timeout"));
        double failureBefore = consumeFailures();

        HoldCreated hold = newService(queue, properties).hold(userId, pid, seatIds(pid).subList(0, 1), token);

        verify(queue).consume(pid, token, userId);
        assertThat(consumeFailures() - failureBefore).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM seat_hold WHERE id = ?", String.class,
                hold.holdId())).isEqualTo("HELD");
    }

    @Test
    @DisplayName("처리열 확인이 Redis 예외로 실패하면 게이트를 부르지 않고 DB 판정으로 201(fail-open)")
    void queueCheckFailureFailsOpen() throws IOException {
        long pid = newPerformance();
        long userId = fixtures.newUser();
        BookingRedis deadRedis = TestBookingRedis.connectTo("localhost", closedPort(), properties);
        try {
            BookingQueueService deadQueue = new BookingQueueService(new BookingQueueRepository(deadRedis),
                    seatCatalog, dbClock, properties);
            RedisSeatGate spyGate = mock(RedisSeatGate.class);
            SeatHoldService service = new SeatHoldService(seatCatalog, seatHoldTransaction, seatHoldRepository,
                    seatRepository, paymentRepository, dbClock, spyGate, deadQueue, properties, meterRegistry);
            // 토큰 주인 확인은 Redis 없이 하므로 장애 중에도 남의 토큰은 403이다
            assertThatThrownBy(() -> service.hold(userId, pid, seatIds(pid).subList(0, 1),
                    (userId + 1) + "." + UUID.randomUUID()))
                    .extracting("code").isEqualTo(BookingErrorCode.NOT_ADMITTED);
            double gateErrorBefore = meterRegistry.counter("booking.hold.gate", "result", "error").count();
            double dbOkBefore = meterRegistry.counter("booking.hold.db", "result", "ok").count();
            double acquiredBefore = meterRegistry.counter("booking.hold.fallback", "result", "acquired").count();
            double consumeFailureBefore = consumeFailures();

            HoldCreated hold = service.hold(userId, pid, seatIds(pid).subList(0, 1), userId + "." + UUID.randomUUID());

            assertThat(hold.holdId()).isPositive();
            verifyNoInteractions(spyGate);
            assertThat(meterRegistry.counter("booking.hold.gate", "result", "error").count() - gateErrorBefore)
                    .isEqualTo(1);
            assertThat(meterRegistry.counter("booking.hold.db", "result", "ok").count() - dbOkBefore).isEqualTo(1);
            assertThat(meterRegistry.counter("booking.hold.fallback", "result", "acquired").count() - acquiredBefore)
                    .isEqualTo(1);
            assertThat(consumeFailures() - consumeFailureBefore).isEqualTo(1);
            assertThat(holdCount(pid)).isEqualTo(1);
        } finally {
            deadRedis.destroy();
        }
    }

    @Test
    @DisplayName("booking.queue.required=false면 토큰 없이 선점하고 처리열을 확인·소모하지 않는다")
    void queueNotRequired() {
        long pid = newPerformance();
        long userId = fixtures.newUser();
        BookingQueueService queue = mock(BookingQueueService.class);

        HoldCreated hold = newService(queue, with(properties.gate().enabled(), false))
                .hold(userId, pid, seatIds(pid).subList(0, 1), null);

        assertThat(hold.holdId()).isPositive();
        verifyNoInteractions(queue);
    }

    @Test
    @DisplayName("게이트 OFF여도 처리열 확인과 소모는 한다")
    void gateOffStillChecksQueue() {
        long pid = newPerformance();
        List<Long> target = seatIds(pid).subList(0, 1);
        long userId = fixtures.newUser();
        RedisSeatGate offGate = mock(RedisSeatGate.class);
        SeatHoldService gateOff = new SeatHoldService(seatCatalog, seatHoldTransaction, seatHoldRepository,
                seatRepository, paymentRepository, dbClock, offGate, queueService, with(false, true), meterRegistry);

        assertThatThrownBy(() -> gateOff.hold(userId, pid, target, null))
                .extracting("code").isEqualTo(BookingErrorCode.NOT_ADMITTED);
        String token = fixtures.admit(pid, userId);
        gateOff.hold(userId, pid, target, token);

        assertThat(processingScore(pid, token)).isNull();
        verifyNoInteractions(offGate);
    }

    private SeatHoldService newService(BookingQueueService queue, BookingProperties props) {
        return new SeatHoldService(seatCatalog, seatHoldTransaction, seatHoldRepository, seatRepository,
                paymentRepository, dbClock, gate, queue, props, meterRegistry);
    }

    private BookingProperties with(boolean gateEnabled, boolean queueRequired) {
        BookingProperties p = properties;
        Queue q = p.queue();
        return new BookingProperties(p.seed(), p.redis(), p.hold(), p.payment(),
                new Gate(gateEnabled, p.gate().fallbackMaxConcurrency()),
                new Queue(q.capacity(), q.processingTokenTtl(), q.waitingTokenTtl(), q.promoteIntervalMs(),
                        queueRequired),
                p.seatmap(), p.outbox(), p.ops(), p.pg(), p.fakePg());
    }

    private long newPerformance() {
        long pid = fixtures.newPerformance(1, 1, 4);
        performances.add(pid);
        return pid;
    }

    private Double processingScore(long pid, String token) {
        return redis().opsForZSet().score(QueueKeys.processing(pid), token);
    }

    private long holdCount(long pid) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM seat_hold WHERE performance_id = ?", Long.class, pid);
    }

    private double counterSum(String name) {
        return meterRegistry.find(name).counters().stream().mapToDouble(Counter::count).sum();
    }

    private double consumeFailures() {
        return meterRegistry.counter("booking.redis.reflect.failure", "op", "queue_consume").count();
    }

    private StringRedisTemplate redis() {
        return bookingRedis.template();
    }

    private List<Long> seatIds(long pid) {
        return jdbcTemplate.queryForList("SELECT id FROM seat WHERE performance_id = ? ORDER BY id", Long.class, pid);
    }

    private static int closedPort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
