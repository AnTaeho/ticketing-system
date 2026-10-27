package com.example.ticketing.booking.ops;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;

import com.example.ticketing.booking.BookingProperties;
import com.example.ticketing.booking.common.BookingRedis;
import com.example.ticketing.booking.common.DbClock;
import com.example.ticketing.booking.common.SchedulerLease;
import com.example.ticketing.booking.common.TestBookingRedis;
import com.example.ticketing.booking.hold.SeatHoldRepository;
import com.example.ticketing.booking.hold.SeatHoldService;
import com.example.ticketing.booking.hold.SeatHoldTransaction;
import com.example.ticketing.booking.hold.dto.HoldCreated;
import com.example.ticketing.booking.hold.gate.BookingKeys;
import com.example.ticketing.booking.hold.gate.RedisSeatGate;
import com.example.ticketing.booking.payment.BookingPaymentRepository;
import com.example.ticketing.booking.payment.BookingPaymentService;
import com.example.ticketing.booking.payment.StubPgClient;
import com.example.ticketing.booking.payment.StubPgTestConfig;
import com.example.ticketing.booking.payment.dto.PaymentResponse;
import com.example.ticketing.booking.payment.pg.PgResult;
import com.example.ticketing.booking.purchase.BookingService;
import com.example.ticketing.booking.purchase.BookingTransaction;
import com.example.ticketing.booking.purchase.BookingViewReader;
import com.example.ticketing.booking.queue.BookingQueueService;
import com.example.ticketing.booking.seat.SeatCatalog;
import com.example.ticketing.booking.seat.SeatRepository;
import com.example.ticketing.booking.support.BookingFixtures;
import com.example.ticketing.booking.support.BookingIntegrationTest;
import com.example.ticketing.booking.support.RedisKeyCleaner;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.IOException;
import java.net.ServerSocket;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;

// 대사 빈은 테스트에서 꺼져 있어 new로 만들고 대상 메서드를 직접 부른다. 막 생긴 키로 보이지 않게 키 TTL을 당겨 둔다
@BookingIntegrationTest
@Import(StubPgTestConfig.class)
class OrphanGateKeyReconcilerTest {

    private static final String LEASE_KEY = "sched:lease:" + OrphanGateKeyReconciler.LEASE;

    @Autowired
    private SchedulerLease lease;
    @Autowired
    private BookingRedis bookingRedis;
    @Autowired
    private NamedParameterJdbcTemplate namedJdbc;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private BookingProperties properties;
    @Autowired
    private MeterRegistry meterRegistry;
    @Autowired
    private BookingFixtures fixtures;
    @Autowired
    private RedisKeyCleaner cleaner;
    @Autowired
    private SeatHoldService seatHoldService;
    @Autowired
    private SeatHoldTransaction seatHoldTransaction;
    @Autowired
    private SeatHoldRepository seatHoldRepository;
    @Autowired
    private SeatRepository seatRepository;
    @Autowired
    private BookingPaymentRepository paymentRepository;
    @Autowired
    private BookingQueueService queueService;
    @Autowired
    private SeatCatalog seatCatalog;
    @Autowired
    private DbClock dbClock;
    @Autowired
    private BookingPaymentService paymentService;
    @Autowired
    private BookingTransaction bookingTransaction;
    @Autowired
    private BookingViewReader viewReader;
    @Autowired
    private StubPgClient stubPg;

    private final List<Long> pids = new ArrayList<>();
    private StringRedisTemplate redis;
    private Duration holdTtl;

    @BeforeEach
    void setUp() {
        stubPg.reset();
        redis = bookingRedis.template();
        holdTtl = properties.hold().ttl();
    }

    @AfterEach
    void tearDown() {
        stubPg.reset();
        pids.forEach(cleaner::clean);
        redis.delete(LEASE_KEY);
    }

    @Test
    @DisplayName("모르는 토큰의 좌석·userhold 키는 지워지고, 숫자만 이어 붙인 다른 회차 키는 패턴에 걸리지 않는다")
    void unknownTokenKeysAreRemoved() {
        long pid = newPerformance();
        long seatId = seatIds(pid).get(0);
        String seatKey = BookingKeys.seat(pid, seatId);
        String userHoldKey = BookingKeys.userHold(pid, 42L);
        setAged(seatKey, UUID.randomUUID().toString());
        setAged(userHoldKey, UUID.randomUUID().toString());
        long lookalikePid = pid * 10;
        pids.add(lookalikePid);
        String lookalike = BookingKeys.seat(lookalikePid, seatId);
        setAged(lookalike, UUID.randomUUID().toString());
        double seatBefore = removedCount("seat");
        double userHoldBefore = removedCount("userhold");

        OrphanGateKeyReconciler.Removed removed = reconciler().reconcileOne(pid);

        assertThat(removed).isEqualTo(new OrphanGateKeyReconciler.Removed(1, 1));
        assertThat(redis.hasKey(seatKey)).isFalse();
        assertThat(redis.hasKey(userHoldKey)).isFalse();
        assertThat(redis.hasKey(lookalike)).isTrue();
        assertThat(removedCount("seat") - seatBefore).isEqualTo(1);
        assertThat(removedCount("userhold") - userHoldBefore).isEqualTo(1);
    }

    @Test
    @DisplayName("확정 좌석(SOLD) 키는 남고, 예매를 취소했는데 키 해제가 실패해 남은 키는 지워진다")
    void cancelledBookingSeatKeysAreRemoved() {
        long pid = newPerformance();
        long userId = fixtures.newUser();
        List<Long> seats = seatIds(pid).subList(0, 2);
        HoldCreated hold = seatHoldService.hold(userId, pid, seats, fixtures.admit(pid, userId));
        PaymentResponse paid = paymentService.pay(userId, hold.holdId(), UUID.randomUUID().toString(),
                Optional.empty());
        assertThat(paid.status()).isEqualTo(PaymentResponse.Status.CONFIRMED);
        String token = holdToken(hold.holdId());

        assertThat(reconciler().reconcileOne(pid)).isEqualTo(new OrphanGateKeyReconciler.Removed(0, 0));
        seats.forEach(seatId -> assertThat(redis.opsForValue().get(BookingKeys.seat(pid, seatId))).isEqualTo(token));

        // 취소 뒤 좌석 키 해제(releaseSeatsOnlyQuietly)가 실패한 상황: 반영을 목 게이트로 삼킨다
        new BookingService(bookingTransaction, viewReader, mock(RedisSeatGate.class), properties)
                .cancel(userId, paid.bookingId());
        seats.forEach(seatId -> assertThat(redis.hasKey(BookingKeys.seat(pid, seatId))).isTrue());

        assertThat(reconciler().reconcileOne(pid)).isEqualTo(new OrphanGateKeyReconciler.Removed(2, 0));
        assertThat(cleaner.gateKeys(pid)).isEmpty();
    }

    @Test
    @DisplayName("살아 있는 hold(HELD·PAYING)의 좌석 키와 userhold 키는 남는다")
    void liveHoldKeysStay() {
        long pid = newPerformance();
        List<Long> ids = seatIds(pid);
        long heldUser = fixtures.newUser();
        long payingUser = fixtures.newUser();
        HoldCreated held = seatHoldService.hold(heldUser, pid, ids.subList(0, 2), fixtures.admit(pid, heldUser));
        HoldCreated paying = seatHoldService.hold(payingUser, pid, ids.subList(2, 3), fixtures.admit(pid, payingUser));
        stubPg.approveWith(PgResult.unknown("timeout"));
        assertThat(paymentService.pay(payingUser, paying.holdId(), UUID.randomUUID().toString(), Optional.empty())
                .status()).isEqualTo(PaymentResponse.Status.UNKNOWN);
        assertThat(holdStatus(paying.holdId())).isEqualTo("PAYING");
        List<String> keys = cleaner.gateKeys(pid);
        assertThat(keys).hasSize(5);
        // HELD 키만 막 생긴 키로 보인다. 결제 중 키는 pay_deadline까지 늘어나 이미 검사 대상이다
        List.of(BookingKeys.userHold(pid, heldUser), BookingKeys.seat(pid, ids.get(0)),
                BookingKeys.seat(pid, ids.get(1))).forEach(this::age);
        double skippedBefore = skippedCount();

        assertThat(reconciler().reconcileOne(pid)).isEqualTo(new OrphanGateKeyReconciler.Removed(0, 0));

        assertThat(skippedCount() - skippedBefore).isZero();
        assertThat(cleaner.gateKeys(pid)).containsExactlyInAnyOrderElementsOf(keys);
        assertThat(redis.opsForValue().get(BookingKeys.userHold(pid, heldUser))).isEqualTo(holdToken(held.holdId()));
        assertThat(redis.opsForValue().get(BookingKeys.userHold(pid, payingUser)))
                .isEqualTo(holdToken(paying.holdId()));
    }

    @Test
    @DisplayName("held_until이 지나 남이 가져간 좌석: 원래 토큰 키는 지워지고, 읽은 뒤 새 선점 토큰으로 바뀐 키는 남는다")
    void seatTakenOverByAnotherHold() {
        long pid = newPerformance();
        List<Long> target = seatIds(pid).subList(0, 2);
        long userA = fixtures.newUser();
        long userB = fixtures.newUser();
        HoldCreated holdA = seatHoldService.hold(userA, pid, target, fixtures.admit(pid, userA));
        String tokenA = holdToken(holdA.holdId());
        target.forEach(seatId -> age(BookingKeys.seat(pid, seatId)));
        age(BookingKeys.userHold(pid, userA));
        jdbcTemplate.update("UPDATE seat_hold SET expires_at = NOW(6) - INTERVAL 1 MINUTE WHERE id = ?", holdA.holdId());
        jdbcTemplate.update("UPDATE seat SET held_until = NOW(6) - INTERVAL 1 MINUTE WHERE hold_id = ?", holdA.holdId());
        // A의 키가 남아 게이트가 막으므로 B는 게이트 없이 DB 판정으로 잡는다
        HoldCreated holdB = gateOffService().hold(userB, pid, target, fixtures.admit(pid, userB));
        String tokenB = holdToken(holdB.holdId());
        assertThat(heldSeats(pid, holdB.holdId())).containsExactlyElementsOf(target);
        String raced = BookingKeys.seat(pid, target.get(0));
        String stale = BookingKeys.seat(pid, target.get(1));

        // 대사가 키를 읽은 뒤 DB를 보기 전에 B의 선점 키가 같은 이름으로 들어온 상황
        OrphanGateKeyReconciler.Removed removed = reconciler(hooked((sql, queriedPid) -> {
            if (sql.contains("JOIN seat s")) {
                setAged(raced, tokenB);
            }
        }), catalogOf(List.of(pid))).reconcileOne(pid);

        assertThat(removed.seats()).isEqualTo(1);
        assertThat(redis.hasKey(stale)).isFalse();
        assertThat(redis.opsForValue().get(raced)).isEqualTo(tokenB);
        // 다음 주기에도 B의 키는 B의 좌석 행과 맞아 남는다
        assertThat(reconciler().reconcileOne(pid).seats()).isZero();
        assertThat(redis.opsForValue().get(raced)).isEqualTo(tokenB);
        assertThat(redis.opsForValue().get(BookingKeys.userHold(pid, userA))).isEqualTo(tokenA);
    }

    @Test
    @DisplayName("PTTL이 hold.ttl - 10초 초과·hold.ttl 이하인 막 생긴 키는 모르는 토큰이어도 건너뛴다")
    void freshKeysAreSkipped() {
        long pid = newPerformance();
        List<Long> ids = seatIds(pid);
        String fresh = BookingKeys.seat(pid, ids.get(0));
        String older = BookingKeys.seat(pid, ids.get(1));
        String longer = BookingKeys.seat(pid, ids.get(2));
        redis.opsForValue().set(fresh, UUID.randomUUID().toString(), holdTtl);
        redis.opsForValue().set(older, UUID.randomUUID().toString(), holdTtl.minusSeconds(11));
        redis.opsForValue().set(longer, UUID.randomUUID().toString(), holdTtl.plusMinutes(1));
        double skippedBefore = skippedCount();

        assertThat(reconciler().reconcileOne(pid)).isEqualTo(new OrphanGateKeyReconciler.Removed(2, 0));

        assertThat(skippedCount() - skippedBefore).isEqualTo(1);
        assertThat(redis.hasKey(fresh)).isTrue();
        assertThat(redis.hasKey(older)).isFalse();
        assertThat(redis.hasKey(longer)).isFalse();
    }

    @Test
    @DisplayName("만료·RELEASED hold의 userhold 키와 좌석 키는 지워진다")
    void expiredAndReleasedHoldKeysAreRemoved() {
        long pid = newPerformance();
        List<Long> ids = seatIds(pid);
        long expiredUser = fixtures.newUser();
        long releasedUser = fixtures.newUser();
        HoldCreated expired = seatHoldService.hold(expiredUser, pid, ids.subList(0, 1),
                fixtures.admit(pid, expiredUser));
        HoldCreated released = seatHoldService.hold(releasedUser, pid, ids.subList(1, 2),
                fixtures.admit(pid, releasedUser));
        cleaner.gateKeys(pid).forEach(this::age);
        // 만료 처리와 선점 취소의 DB 쪽만 반영하고 키 해제는 실패한 상황
        jdbcTemplate.update("UPDATE seat_hold SET status = 'EXPIRED' WHERE id = ?", expired.holdId());
        jdbcTemplate.update("UPDATE seat SET status = 'AVAILABLE', hold_id = NULL, held_until = NULL WHERE hold_id = ?",
                expired.holdId());
        seatHoldTransaction.releaseHold(releasedUser, released.holdId());
        assertThat(holdStatus(released.holdId())).isEqualTo("RELEASED");
        assertThat(cleaner.gateKeys(pid)).hasSize(4);

        assertThat(reconciler().reconcileOne(pid)).isEqualTo(new OrphanGateKeyReconciler.Removed(2, 2));
        assertThat(cleaner.gateKeys(pid)).isEmpty();
    }

    @Test
    @DisplayName("두 인스턴스가 동시에 돌면 리스를 잡은 하나만 대사하고, 끝나면 리스를 푼다")
    void onlyOneInstanceRuns() throws Exception {
        long pid = newPerformance();
        String key = BookingKeys.seat(pid, seatIds(pid).get(0));
        setAged(key, UUID.randomUUID().toString());
        AtomicInteger seatQueries = new AtomicInteger();
        NamedParameterJdbcTemplate slow = hooked((sql, queriedPid) -> {
            if (sql.contains("JOIN seat s")) {
                seatQueries.incrementAndGet();
                sleep(300);
            }
        });
        List<OrphanGateKeyReconciler> instances = List.of(
                reconciler(new SchedulerLease(bookingRedis), slow, catalogOf(List.of(pid))),
                reconciler(new SchedulerLease(bookingRedis), slow, catalogOf(List.of(pid))));
        ExecutorService pool = Executors.newFixedThreadPool(instances.size());
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (OrphanGateKeyReconciler instance : instances) {
                futures.add(pool.submit(() -> {
                    start.await();
                    instance.reconcile();
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> future : futures) {
                future.get(10, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(seatQueries.get()).isEqualTo(1);
        assertThat(redis.hasKey(key)).isFalse();
        assertThat(redis.hasKey(LEASE_KEY)).isFalse();
    }

    @Test
    @DisplayName("예매 중이 아닌 회차의 키는 건드리지 않는다")
    void notOpenPerformanceIsUntouched() {
        long open = newPerformance();
        LocalDateTime now = fixtures.dbNow();
        long notOpen = fixtures.newPerformance(fixtures.newShow(), now.plusHours(1), now.plusDays(1), 1, 1, 4);
        pids.add(notOpen);
        String openKey = BookingKeys.seat(open, seatIds(open).get(0));
        String notOpenKey = BookingKeys.seat(notOpen, seatIds(notOpen).get(0));
        setAged(openKey, UUID.randomUUID().toString());
        setAged(notOpenKey, UUID.randomUUID().toString());
        List<Long> mine = List.of(open, notOpen);
        // 새 인스턴스라 캐시 없이 DB로 예매 중 회차를 고른다. 이 테스트의 회차만 남겨 다른 테스트 키를 건드리지 않는다
        SeatCatalog freshCatalog = new SeatCatalog(jdbcTemplate, properties) {
            @Override
            public List<Long> openPerformanceIds() {
                return super.openPerformanceIds().stream().filter(mine::contains).toList();
            }
        };

        reconciler(namedJdbc, freshCatalog).reconcile();

        assertThat(redis.hasKey(openKey)).isFalse();
        assertThat(redis.hasKey(notOpenKey)).isTrue();
    }

    @Test
    @DisplayName("한 회차의 DB 조회가 실패해도 다음 회차는 대사한다")
    void oneFailingPerformanceDoesNotStopOthers() {
        long broken = newPerformance();
        long healthy = newPerformance();
        String brokenKey = BookingKeys.seat(broken, seatIds(broken).get(0));
        String healthyKey = BookingKeys.seat(healthy, seatIds(healthy).get(0));
        setAged(brokenKey, UUID.randomUUID().toString());
        setAged(healthyKey, UUID.randomUUID().toString());
        NamedParameterJdbcTemplate failing = hooked((sql, queriedPid) -> {
            if (queriedPid == broken) {
                throw new TransientDataAccessResourceException("boom");
            }
        });

        reconciler(failing, catalogOf(List.of(broken, healthy))).reconcileAll();

        assertThat(redis.hasKey(brokenKey)).isTrue();
        assertThat(redis.hasKey(healthyKey)).isFalse();
    }

    @Test
    @DisplayName("Redis 장애면 예외 없이 그 주기를 건너뛰고 리스를 푼다")
    void skipsCycleOnRedisFailure() throws IOException {
        long pid = newPerformance();
        BookingRedis dead = TestBookingRedis.connectTo("localhost", closedPort(), properties);
        try {
            OrphanGateKeyReconciler reconciler = new OrphanGateKeyReconciler(lease, dead, namedJdbc,
                    catalogOf(List.of(pid, pid)), properties, meterRegistry);
            assertThatCode(reconciler::reconcile).doesNotThrowAnyException();
        } finally {
            dead.destroy();
        }
        assertThat(redis.hasKey(LEASE_KEY)).isFalse();
    }

    private OrphanGateKeyReconciler reconciler() {
        return reconciler(namedJdbc, seatCatalog);
    }

    private OrphanGateKeyReconciler reconciler(NamedParameterJdbcTemplate jdbc, SeatCatalog catalog) {
        return reconciler(lease, jdbc, catalog);
    }

    private OrphanGateKeyReconciler reconciler(SchedulerLease schedulerLease, NamedParameterJdbcTemplate jdbc,
                                               SeatCatalog catalog) {
        return new OrphanGateKeyReconciler(schedulerLease, bookingRedis, jdbc, catalog, properties, meterRegistry);
    }

    // 대사의 DB 조회 직전에 끼어들 지점. 두 번째 인자는 조회하는 회차 ID다
    private NamedParameterJdbcTemplate hooked(BiConsumer<String, Long> beforeQuery) {
        return new NamedParameterJdbcTemplate(jdbcTemplate) {
            @Override
            public void query(String sql, SqlParameterSource params, RowCallbackHandler rch) {
                beforeQuery.accept(sql, (Long) params.getValue("pid"));
                super.query(sql, params, rch);
            }

            @Override
            public <T> List<T> queryForList(String sql, SqlParameterSource params, Class<T> elementType) {
                beforeQuery.accept(sql, (Long) params.getValue("pid"));
                return super.queryForList(sql, params, elementType);
            }
        };
    }

    // 운영 SeatCatalog는 예매 중 회차 목록을 10초 묶어 두므로 방금 만든 회차가 빠질 수 있다
    private SeatCatalog catalogOf(List<Long> openIds) {
        return new SeatCatalog(jdbcTemplate, properties) {
            @Override
            public List<Long> openPerformanceIds() {
                return openIds;
            }
        };
    }

    private SeatHoldService gateOffService() {
        BookingProperties p = properties;
        BookingProperties gateOff = new BookingProperties(p.seed(), p.redis(), p.hold(), p.payment(),
                new BookingProperties.Gate(false, p.gate().fallbackMaxConcurrency()), p.queue(), p.seatmap(),
                p.outbox(), p.ops(), p.pg(), p.fakePg());
        return new SeatHoldService(seatCatalog, seatHoldTransaction, seatHoldRepository, seatRepository,
                paymentRepository, dbClock, mock(RedisSeatGate.class), queueService, gateOff, meterRegistry);
    }

    private long newPerformance() {
        long pid = fixtures.newPerformance(1, 1, 6);
        pids.add(pid);
        return pid;
    }

    private void setAged(String key, String value) {
        redis.opsForValue().set(key, value, agedTtl());
    }

    private void age(String key) {
        redis.expire(key, agedTtl());
    }

    private Duration agedTtl() {
        return holdTtl.minusSeconds(30);
    }

    private double removedCount(String kind) {
        return meterRegistry.counter(OrphanGateKeyReconciler.REMOVED_COUNTER, "kind", kind).count();
    }

    private double skippedCount() {
        return meterRegistry.counter(OrphanGateKeyReconciler.SKIPPED_COUNTER, "reason", "fresh").count();
    }

    private List<Long> seatIds(long pid) {
        return jdbcTemplate.queryForList("SELECT id FROM seat WHERE performance_id = ? ORDER BY id", Long.class, pid);
    }

    private List<Long> heldSeats(long pid, long holdId) {
        return jdbcTemplate.queryForList(
                "SELECT id FROM seat WHERE performance_id = ? AND hold_id = ? AND status = 'HELD' ORDER BY id",
                Long.class, pid, holdId);
    }

    private String holdToken(long holdId) {
        return jdbcTemplate.queryForObject("SELECT hold_token FROM seat_hold WHERE id = ?", String.class, holdId);
    }

    private String holdStatus(long holdId) {
        return jdbcTemplate.queryForObject("SELECT status FROM seat_hold WHERE id = ?", String.class, holdId);
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static int closedPort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
