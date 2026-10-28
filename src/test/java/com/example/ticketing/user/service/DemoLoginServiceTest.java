package com.example.ticketing.user.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.ticketing.common.error.ApiException;
import com.example.ticketing.common.error.ErrorCode;
import com.example.ticketing.support.Concurrently;
import com.example.ticketing.support.Concurrently.Results;
import com.example.ticketing.support.IntegrationTest;
import com.example.ticketing.user.dto.DemoLoginResult;
import java.time.Duration;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

@IntegrationTest
class DemoLoginServiceTest {

    private static final int THREAD_COUNT = 10;

    @Autowired
    private DemoLoginService demoLoginService;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("같은 닉네임으로 10명이 동시에 로그인해도 사용자는 1행이고 모두 같은 userId를 받는다")
    void concurrentSameNickname() {
        String nickname = newNickname();

        Results<Long> results = Concurrently.of(THREAD_COUNT)
                .within(Duration.ofSeconds(30))
                .run(i -> demoLoginService.login(nickname).userId());

        assertThat(results.errors()).isEmpty();
        assertThat(results.values()).hasSize(THREAD_COUNT);
        Set<Long> userIds = Set.copyOf(results.values());
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
    @DisplayName("공백을 지운 닉네임이 2자 미만이거나 20자를 넘으면 INVALID_REQUEST로 거절한다")
    void rejectsInvalidLength() {
        for (String invalid : new String[]{null, "", "   ", " 가 ", "가".repeat(21)}) {
            assertThatThrownBy(() -> demoLoginService.login(invalid))
                    .isInstanceOf(ApiException.class)
                    .extracting(e -> ((ApiException) e).getCode())
                    .isEqualTo(ErrorCode.INVALID_REQUEST);
        }
        assertThat(demoLoginService.login("가나").nickname()).isEqualTo("가나");
        assertThat(demoLoginService.login("가".repeat(20)).nickname()).isEqualTo("가".repeat(20));
    }

    private static String newNickname() {
        return "dl-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }
}
