package com.example.ticketing.booking.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.ticketing.booking.common.BookingErrorCode;
import com.example.ticketing.booking.common.BookingException;
import com.example.ticketing.booking.support.BookingIntegrationTest;
import com.example.ticketing.booking.user.dto.DemoLoginResult;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

@BookingIntegrationTest
class DemoLoginServiceTest {

    private static final int THREAD_COUNT = 10;

    @Autowired
    private DemoLoginService demoLoginService;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("같은 닉네임으로 10명이 동시에 로그인해도 사용자는 1행이고 모두 같은 userId를 받는다")
    void concurrentSameNickname() throws InterruptedException {
        String nickname = newNickname();
        ExecutorService executor = Executors.newFixedThreadPool(THREAD_COUNT);
        CountDownLatch ready = new CountDownLatch(THREAD_COUNT);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(THREAD_COUNT);
        List<Throwable> errors = new CopyOnWriteArrayList<>();
        Set<Long> userIds = ConcurrentHashMap.newKeySet();
        AtomicInteger successCount = new AtomicInteger();

        for (int i = 0; i < THREAD_COUNT; i++) {
            executor.submit(() -> {
                try {
                    ready.countDown();
                    start.await();
                    DemoLoginResult result = demoLoginService.login(nickname);
                    userIds.add(result.userId());
                    successCount.incrementAndGet();
                } catch (Throwable t) {
                    errors.add(t);
                } finally {
                    done.countDown();
                }
            });
        }
        ready.await();
        start.countDown();
        assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
        executor.shutdown();

        assertThat(errors).isEmpty();
        assertThat(successCount.get()).isEqualTo(THREAD_COUNT);
        assertThat(userIds).hasSize(1);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM app_user WHERE nickname = ?",
                Long.class, nickname)).isEqualTo(1L);
        Long rowId = jdbcTemplate.queryForObject("SELECT id FROM app_user WHERE nickname = ?", Long.class, nickname);
        assertThat(userIds).containsExactly(rowId);
    }

    @Test
    @DisplayName("앞뒤 공백을 지운 닉네임으로 같은 사용자를 찾는다")
    void trimsNickname() {
        String nickname = newNickname();

        DemoLoginResult first = demoLoginService.login(nickname);
        DemoLoginResult second = demoLoginService.login("  " + nickname + "  ");

        assertThat(second.userId()).isEqualTo(first.userId());
        assertThat(second.nickname()).isEqualTo(nickname);
    }

    @Test
    @DisplayName("공백을 지운 닉네임이 2자 미만이거나 20자를 넘으면 INVALID_REQUEST")
    void rejectsInvalidLength() {
        for (String invalid : new String[]{null, "", "   ", " 가 ", "가".repeat(21)}) {
            assertThatThrownBy(() -> demoLoginService.login(invalid))
                    .isInstanceOf(BookingException.class)
                    .extracting(e -> ((BookingException) e).getCode())
                    .isEqualTo(BookingErrorCode.INVALID_REQUEST);
        }
        assertThat(demoLoginService.login("가나").nickname()).isEqualTo("가나");
        assertThat(demoLoginService.login("가".repeat(20)).nickname()).isEqualTo("가".repeat(20));
    }

    private static String newNickname() {
        return "dl-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }
}
