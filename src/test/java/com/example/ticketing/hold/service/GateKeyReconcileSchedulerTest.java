package com.example.ticketing.hold.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;

import com.example.ticketing.common.config.TicketingProperties;
import com.example.ticketing.common.metrics.Metrics;
import com.example.ticketing.common.metrics.Metrics.Tag;
import com.example.ticketing.common.redis.RedisKeys;
import com.example.ticketing.hold.dto.CreateHoldCommand;
import com.example.ticketing.hold.dto.CreateHoldResult;
import com.example.ticketing.hold.repository.SeatGateRedisStore;
import com.example.ticketing.payment.client.PgResult;
import com.example.ticketing.payment.dto.PayCommand;
import com.example.ticketing.payment.dto.PaymentOutcome;
import com.example.ticketing.payment.dto.PaymentResult;
import com.example.ticketing.payment.service.PaymentService;
import com.example.ticketing.support.Concurrently;
import com.example.ticketing.support.Concurrently.Results;
import com.example.ticketing.support.Counters;
import com.example.ticketing.support.Fixtures;
import com.example.ticketing.support.IntegrationTest;
import com.example.ticketing.support.StubPgClient;
import com.example.ticketing.support.StubPgTestConfig;
import com.example.ticketing.support.TestDb;
import com.example.ticketing.support.TestProperties;
import com.example.ticketing.support.TestRedis;
import com.example.ticketing.support.TestServices;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
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
@IntegrationTest
@Import(StubPgTestConfig.class)
class GateKeyReconcileSchedulerTest {

    private static final String LEASE_KEY = "sched:lease:" + GateKeyReconcileScheduler.LEASE;

    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private TicketingProperties properties;
    @Autowired
    private Fixtures fixtures;
    @Autowired
    private TestDb db;
    @Autowired
    private TestRedis testRedis;
    @Autowired
    private TestServices services;
    @Autowired
    private Counters counters;
    @Autowired
    private SeatHoldService seatHoldService;
    @Autowired
    private SeatHoldTransaction seatHoldTransaction;
    @Autowired
    private PaymentService paymentService;
    @Autowired
    private StubPgClient stubPg;

    private StringRedisTemplate redis;
    private Duration holdTtl;

    @BeforeEach
    void setUp() {
        stubPg.reset();
        redis = testRedis.template();
        holdTtl = properties.hold().ttl();
    }

    @AfterEach
    void tearDown() {
        stubPg.reset();
        redis.delete(LEASE_KEY);
    }

    @Test
    @DisplayName("모르는 토큰의 게이트 키는 지우고 숫자만 이어 붙인 다른 회차 키는 남긴다")
    void unknownTokenKeysAreRemoved() {
        long pid = fixtures.newPerformance(1, 1, 6);
        long seatId = db.seatIds(pid).get(0);
        String seatKey = RedisKeys.seat(pid, seatId);
        String userHoldKey = RedisKeys.userHold(42L, pid);
        setAged(seatKey, UUID.randomUUID().toString());
        setAged(userHoldKey, UUID.randomUUID().toString());
        long lookalikePid = fixtures.track(pid * 10);
        String lookalike = RedisKeys.seat(lookalikePid, seatId);
        setAged(lookalike, UUID.randomUUID().toString());
        double seatBefore = counters.count(Metrics.GATE_ORPHAN_REMOVED, Tag.KIND, "seat");
        double userHoldBefore = counters.count(Metrics.GATE_ORPHAN_REMOVED, Tag.KIND, "userhold");

        GateKeyReconcileScheduler.Removed removed = services.gateKeyReconcileScheduler().build().reconcileOne(pid);

        assertThat(removed).isEqualTo(new GateKeyReconcileScheduler.Removed(1, 1));
        assertThat(redis.hasKey(seatKey)).isFalse();
        assertThat(redis.hasKey(userHoldKey)).isFalse();
        assertThat(redis.hasKey(lookalike)).isTrue();
        assertThat(counters.count(Metrics.GATE_ORPHAN_REMOVED, Tag.KIND, "seat") - seatBefore).isEqualTo(1);
        assertThat(counters.count(Metrics.GATE_ORPHAN_REMOVED, Tag.KIND, "userhold") - userHoldBefore).isEqualTo(1);
    }

    @Test
    @DisplayName("예매를 취소했는데 남은 좌석 키는 지우고 확정 좌석 키는 남긴다")
    void cancelledBookingSeatKeysAreRemoved() {
        long pid = fixtures.newPerformance(1, 1, 6);
        long userId = fixtures.newUser();
        List<Long> seats = db.seatIds(pid).subList(0, 2);
        CreateHoldResult hold = seatHoldService.hold(
                new CreateHoldCommand(userId, pid, seats, fixtures.admit(pid, userId)));
        PaymentResult paid = paymentService.pay(new PayCommand(userId, hold.holdId(), UUID.randomUUID().toString(),
                Optional.empty()));
        assertThat(paid.outcome()).isEqualTo(PaymentOutcome.CONFIRMED);
        String token = db.holdToken(hold.holdId());

        assertThat(services.gateKeyReconcileScheduler().build().reconcileOne(pid))
                .isEqualTo(new GateKeyReconcileScheduler.Removed(0, 0));
        seats.forEach(seatId -> assertThat(redis.opsForValue().get(RedisKeys.seat(pid, seatId))).isEqualTo(token));

        // 취소 뒤 좌석 키 해제가 실패한 상황: 반영 빈을 목으로 바꿔 삼킨다
        services.bookingService(mock(SeatGateReflector.class)).cancel(userId, paid.bookingId());
        seats.forEach(seatId -> assertThat(redis.hasKey(RedisKeys.seat(pid, seatId))).isTrue());

        assertThat(services.gateKeyReconcileScheduler().build().reconcileOne(pid))
                .isEqualTo(new GateKeyReconcileScheduler.Removed(2, 0));
        assertThat(testRedis.gateKeys(pid)).isEmpty();
    }

    @Test
    @DisplayName("살아 있는 선점(HELD·PAYING)의 좌석 키와 사용자 키는 남긴다")
    void liveHoldKeysStay() {
        long pid = fixtures.newPerformance(1, 1, 6);
        List<Long> ids = db.seatIds(pid);
        long heldUser = fixtures.newUser();
        long payingUser = fixtures.newUser();
        CreateHoldResult held = seatHoldService.hold(
                new CreateHoldCommand(heldUser, pid, ids.subList(0, 2), fixtures.admit(pid, heldUser)));
        CreateHoldResult paying = seatHoldService.hold(
                new CreateHoldCommand(payingUser, pid, ids.subList(2, 3), fixtures.admit(pid, payingUser)));
        stubPg.approveWith(PgResult.unknown("timeout"));
        assertThat(paymentService.pay(new PayCommand(payingUser, paying.holdId(), UUID.randomUUID().toString(),
                Optional.empty()))
                .outcome()).isEqualTo(PaymentOutcome.UNKNOWN);
        assertThat(db.holdStatus(paying.holdId())).isEqualTo("PAYING");
        List<String> keys = testRedis.gateKeys(pid);
        assertThat(keys).hasSize(5);
        // HELD 키만 막 생긴 키로 보인다. 결제 중 키는 pay_deadline까지 늘어나 이미 검사 대상이다
        List.of(RedisKeys.userHold(heldUser, pid), RedisKeys.seat(pid, ids.get(0)),
                RedisKeys.seat(pid, ids.get(1))).forEach(this::age);
        double skippedBefore = counters.count(Metrics.GATE_ORPHAN_SKIPPED, Tag.REASON, "fresh");

        assertThat(services.gateKeyReconcileScheduler().build().reconcileOne(pid))
                .isEqualTo(new GateKeyReconcileScheduler.Removed(0, 0));

        assertThat(counters.count(Metrics.GATE_ORPHAN_SKIPPED, Tag.REASON, "fresh") - skippedBefore).isZero();
        assertThat(testRedis.gateKeys(pid)).containsExactlyInAnyOrderElementsOf(keys);
        assertThat(redis.opsForValue().get(RedisKeys.userHold(heldUser, pid))).isEqualTo(db.holdToken(held.holdId()));
        assertThat(redis.opsForValue().get(RedisKeys.userHold(payingUser, pid)))
                .isEqualTo(db.holdToken(paying.holdId()));
    }

    @Test
    @DisplayName("남이 가져간 좌석의 옛 토큰 키는 지우고 대사 도중 새 토큰으로 바뀐 키는 남긴다")
    void seatTakenOverByAnotherHold() {
        long pid = fixtures.newPerformance(1, 1, 6);
        List<Long> target = db.seatIds(pid).subList(0, 2);
        long userA = fixtures.newUser();
        long userB = fixtures.newUser();
        CreateHoldResult holdA = seatHoldService.hold(
                new CreateHoldCommand(userA, pid, target, fixtures.admit(pid, userA)));
        String tokenA = db.holdToken(holdA.holdId());
        target.forEach(seatId -> age(RedisKeys.seat(pid, seatId)));
        age(RedisKeys.userHold(userA, pid));
        db.expireHoldAndSeats(holdA.holdId());
        // A의 키가 남아 게이트가 막으므로 B는 게이트 없이 DB 판정으로 잡는다
        CreateHoldResult holdB = gateOffService().hold(
                new CreateHoldCommand(userB, pid, target, fixtures.admit(pid, userB)));
        String tokenB = db.holdToken(holdB.holdId());
        assertThat(db.heldSeats(pid, holdB.holdId())).containsExactlyElementsOf(target);
        String raced = RedisKeys.seat(pid, target.get(0));
        String stale = RedisKeys.seat(pid, target.get(1));

        // 대사가 키를 읽은 뒤 DB를 보기 전에 B의 선점 키가 같은 이름으로 들어온 상황
        GateKeyReconcileScheduler.Removed removed = services.gateKeyReconcileScheduler()
                .jdbc(hooked((sql, queriedPid) -> {
                    if (sql.contains("JOIN seat s")) {
                        setAged(raced, tokenB);
                    }
                }))
                .catalog(services.catalogOf(List.of(pid)))
                .build()
                .reconcileOne(pid);

        assertThat(removed.seats()).isEqualTo(1);
        assertThat(redis.hasKey(stale)).isFalse();
        assertThat(redis.opsForValue().get(raced)).isEqualTo(tokenB);

        // 다음 주기에도 B의 키는 B의 좌석 행과 맞아 남는다
        assertThat(services.gateKeyReconcileScheduler().build().reconcileOne(pid).seats()).isZero();
        assertThat(redis.opsForValue().get(raced)).isEqualTo(tokenB);
        assertThat(redis.opsForValue().get(RedisKeys.userHold(userA, pid))).isEqualTo(tokenA);
    }

    @Test
    @DisplayName("남은 수명이 hold.ttl에서 10초 안쪽인 막 생긴 키는 모르는 토큰이어도 건너뛴다")
    void freshKeysAreSkipped() {
        long pid = fixtures.newPerformance(1, 1, 6);
        List<Long> ids = db.seatIds(pid);
        String fresh = RedisKeys.seat(pid, ids.get(0));
        String older = RedisKeys.seat(pid, ids.get(1));
        String longer = RedisKeys.seat(pid, ids.get(2));
        redis.opsForValue().set(fresh, UUID.randomUUID().toString(), holdTtl);
        redis.opsForValue().set(older, UUID.randomUUID().toString(), holdTtl.minusSeconds(11));
        redis.opsForValue().set(longer, UUID.randomUUID().toString(), holdTtl.plusMinutes(1));
        double skippedBefore = counters.count(Metrics.GATE_ORPHAN_SKIPPED, Tag.REASON, "fresh");

        assertThat(services.gateKeyReconcileScheduler().build().reconcileOne(pid))
                .isEqualTo(new GateKeyReconcileScheduler.Removed(2, 0));

        assertThat(counters.count(Metrics.GATE_ORPHAN_SKIPPED, Tag.REASON, "fresh") - skippedBefore).isEqualTo(1);
        assertThat(redis.hasKey(fresh)).isTrue();
        assertThat(redis.hasKey(older)).isFalse();
        assertThat(redis.hasKey(longer)).isFalse();
    }

    @Test
    @DisplayName("만료되거나 취소된 선점의 사용자 키와 좌석 키는 지운다")
    void expiredAndReleasedHoldKeysAreRemoved() {
        long pid = fixtures.newPerformance(1, 1, 6);
        List<Long> ids = db.seatIds(pid);
        long expiredUser = fixtures.newUser();
        long releasedUser = fixtures.newUser();
        CreateHoldResult expired = seatHoldService.hold(new CreateHoldCommand(expiredUser, pid, ids.subList(0, 1),
                fixtures.admit(pid, expiredUser)));
        CreateHoldResult released = seatHoldService.hold(new CreateHoldCommand(releasedUser, pid, ids.subList(1, 2),
                fixtures.admit(pid, releasedUser)));
        testRedis.gateKeys(pid).forEach(this::age);
        // 만료 처리와 선점 취소의 DB 쪽만 반영하고 키 해제는 실패한 상황
        jdbcTemplate.update("UPDATE seat_hold SET status = 'EXPIRED' WHERE id = ?", expired.holdId());
        jdbcTemplate.update("UPDATE seat SET status = 'AVAILABLE', hold_id = NULL, held_until = NULL WHERE hold_id = ?",
                expired.holdId());
        seatHoldTransaction.releaseHold(releasedUser, released.holdId());
        assertThat(db.holdStatus(released.holdId())).isEqualTo("RELEASED");
        assertThat(testRedis.gateKeys(pid)).hasSize(4);

        assertThat(services.gateKeyReconcileScheduler().build().reconcileOne(pid))
                .isEqualTo(new GateKeyReconcileScheduler.Removed(2, 2));

        assertThat(testRedis.gateKeys(pid)).isEmpty();
    }

    @Test
    @DisplayName("두 인스턴스가 동시에 돌면 리스를 잡은 하나만 대사하고 끝나면 리스를 푼다")
    void onlyOneInstanceRuns() {
        long pid = fixtures.newPerformance(1, 1, 6);
        String key = RedisKeys.seat(pid, db.seatIds(pid).get(0));
        setAged(key, UUID.randomUUID().toString());
        AtomicInteger seatQueries = new AtomicInteger();
        NamedParameterJdbcTemplate slow = hooked((sql, queriedPid) -> {
            if (sql.contains("JOIN seat s")) {
                seatQueries.incrementAndGet();
                sleep(300);
            }
        });
        List<GateKeyReconcileScheduler> instances = List.of(
                services.gateKeyReconcileScheduler().lease(services.newLease()).jdbc(slow)
                        .catalog(services.catalogOf(List.of(pid))).build(),
                services.gateKeyReconcileScheduler().lease(services.newLease()).jdbc(slow)
                        .catalog(services.catalogOf(List.of(pid))).build());

        Results<Void> results = Concurrently.of(instances.size()).within(Duration.ofSeconds(10))
                .runAll(i -> instances.get(i).run());

        assertThat(results.errors()).isEmpty();
        assertThat(seatQueries.get()).isEqualTo(1);
        assertThat(redis.hasKey(key)).isFalse();
        assertThat(redis.hasKey(LEASE_KEY)).isFalse();
    }

    @Test
    @DisplayName("예매 중이 아닌 회차의 키는 건드리지 않는다")
    void notOpenPerformanceIsUntouched() {
        long open = fixtures.newPerformance(1, 1, 6);
        LocalDateTime now = fixtures.dbNow();
        long notOpen = fixtures.newPerformance(fixtures.newShow(), now.plusHours(1), now.plusDays(1), 1, 1, 4);
        String openKey = RedisKeys.seat(open, db.seatIds(open).get(0));
        String notOpenKey = RedisKeys.seat(notOpen, db.seatIds(notOpen).get(0));
        setAged(openKey, UUID.randomUUID().toString());
        setAged(notOpenKey, UUID.randomUUID().toString());

        services.gateKeyReconcileScheduler().catalog(services.openCatalogAmong(List.of(open, notOpen))).build().run();

        assertThat(redis.hasKey(openKey)).isFalse();
        assertThat(redis.hasKey(notOpenKey)).isTrue();
    }

    @Test
    @DisplayName("한 회차의 DB 조회가 실패해도 다음 회차는 대사한다")
    void oneFailingPerformanceDoesNotStopOthers() {
        long broken = fixtures.newPerformance(1, 1, 6);
        long healthy = fixtures.newPerformance(1, 1, 6);
        String brokenKey = RedisKeys.seat(broken, db.seatIds(broken).get(0));
        String healthyKey = RedisKeys.seat(healthy, db.seatIds(healthy).get(0));
        setAged(brokenKey, UUID.randomUUID().toString());
        setAged(healthyKey, UUID.randomUUID().toString());
        NamedParameterJdbcTemplate failing = hooked((sql, queriedPid) -> {
            if (queriedPid == broken) {
                throw new TransientDataAccessResourceException("boom");
            }
        });

        services.gateKeyReconcileScheduler().jdbc(failing).catalog(services.catalogOf(List.of(broken, healthy)))
                .build().reconcileAll();

        assertThat(redis.hasKey(brokenKey)).isTrue();
        assertThat(redis.hasKey(healthyKey)).isFalse();
    }

    @Test
    @DisplayName("Redis 장애면 예외 없이 그 주기를 건너뛰고 리스를 푼다")
    void skipsCycleOnRedisFailure() {
        long pid = fixtures.newPerformance(1, 1, 6);
        GateKeyReconcileScheduler reconciler = services.gateKeyReconcileScheduler()
                .gateStore(TestServices.gateStore(testRedis.dead()))
                .catalog(services.catalogOf(List.of(pid, pid)))
                .build();

        assertThatCode(reconciler::run).doesNotThrowAnyException();

        assertThat(redis.hasKey(LEASE_KEY)).isFalse();
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

    private SeatHoldService gateOffService() {
        TicketingProperties gateOff = TestProperties.from(properties).gateEnabled(false).build();
        return services.seatHoldService().properties(gateOff).gateStore(mock(SeatGateRedisStore.class)).build();
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

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
