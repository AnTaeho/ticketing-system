package com.example.ticketing.common.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.ticketing.support.IntegrationTest;
import com.example.ticketing.support.TestMvc;
import com.example.ticketing.support.TestRedis;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.health.actuate.endpoint.HealthEndpoint;
import org.springframework.boot.health.actuate.endpoint.HttpCodeStatusMapper;
import org.springframework.boot.health.actuate.endpoint.StatusAggregator;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.Status;
import org.springframework.test.web.servlet.MockMvc;

@IntegrationTest
class TicketingRedisHealthIndicatorTest {

    @Autowired
    private TicketingRedisHealthIndicator ticketingRedisHealthIndicator;
    @Autowired
    private HealthEndpoint healthEndpoint;
    @Autowired
    private StatusAggregator statusAggregator;
    @Autowired
    private HttpCodeStatusMapper httpCodeStatusMapper;
    @Autowired
    private TestRedis testRedis;
    @Autowired
    private TestMvc testMvc;

    @Test
    @DisplayName("예매 Redis가 PING에 답하면 UP이다")
    void upWhenRedisAnswers() {
        assertThat(ticketingRedisHealthIndicator.health().getStatus()).isEqualTo(Status.UP);
    }

    @Test
    @DisplayName("닫힌 포트면 1초 안에 DEGRADED와 원인을 돌려준다")
    void degradedFastWhenRedisDown() {
        TicketingRedisHealthIndicator indicator = new TicketingRedisHealthIndicator(testRedis.dead());

        long started = System.nanoTime();
        Health health = indicator.health();
        Duration elapsed = Duration.ofNanos(System.nanoTime() - started);

        assertThat(health.getStatus().getCode()).isEqualTo("DEGRADED");
        assertThat(health.getDetails()).containsKey("error");
        assertThat(elapsed).isLessThan(Duration.ofSeconds(1));
    }

    @Test
    @DisplayName("헬스 확인(/actuator/health)은 기본 redis 대신 bookingRedis 항목으로 UP이다")
    void actuatorHealthUp() throws Exception {
        MockMvc mockMvc = testMvc.mockMvc();

        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));

        assertThat(healthEndpoint.healthForPath("bookingRedis").getStatus()).isEqualTo(Status.UP);
        assertThat(healthEndpoint.healthForPath("redis")).isNull();
    }

    @Test
    @DisplayName("상태가 DEGRADED면 UP과 DOWN 사이로 모이고 HTTP 200이다")
    void degradedStatusPolicy() {
        Status degraded = new Status("DEGRADED");

        assertThat(statusAggregator.getAggregateStatus(Status.UP, degraded)).isEqualTo(degraded);
        assertThat(statusAggregator.getAggregateStatus(Status.DOWN, degraded)).isEqualTo(Status.DOWN);
        assertThat(httpCodeStatusMapper.getStatusCode(degraded)).isEqualTo(200);
        assertThat(httpCodeStatusMapper.getStatusCode(Status.UP)).isEqualTo(200);
        assertThat(httpCodeStatusMapper.getStatusCode(Status.DOWN)).isEqualTo(503);
        assertThat(httpCodeStatusMapper.getStatusCode(Status.OUT_OF_SERVICE)).isEqualTo(503);
    }
}
