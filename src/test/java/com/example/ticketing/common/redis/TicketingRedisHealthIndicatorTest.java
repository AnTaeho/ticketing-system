package com.example.ticketing.common.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.ticketing.common.config.TicketingProperties;
import com.example.ticketing.support.IntegrationTest;
import java.io.IOException;
import java.net.ServerSocket;
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
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

@IntegrationTest
class TicketingRedisHealthIndicatorTest {

    @Autowired
    private TicketingRedisHealthIndicator indicator;
    @Autowired
    private TicketingProperties props;
    @Autowired
    private HealthEndpoint healthEndpoint;
    @Autowired
    private StatusAggregator statusAggregator;
    @Autowired
    private HttpCodeStatusMapper httpCodeStatusMapper;
    @Autowired
    private WebApplicationContext context;

    @Test
    @DisplayName("예매 Redis에 PING이 되면 UP")
    void upWhenRedisAnswers() {
        assertThat(indicator.health().getStatus()).isEqualTo(Status.UP);
    }

    @Test
    @DisplayName("닫힌 포트면 1초 안에 DEGRADED와 원인을 돌려준다")
    void degradedFastWhenRedisDown() throws IOException {
        TicketingRedis dead = TestTicketingRedis.connectTo("localhost", closedPort(), props);
        try {
            long started = System.nanoTime();
            Health health = new TicketingRedisHealthIndicator(dead).health();
            Duration elapsed = Duration.ofNanos(System.nanoTime() - started);

            assertThat(health.getStatus().getCode()).isEqualTo("DEGRADED");
            assertThat(health.getDetails()).containsKey("error");
            assertThat(elapsed).isLessThan(Duration.ofSeconds(1));
        } finally {
            dead.destroy();
        }
    }

    @Test
    @DisplayName("/actuator/health는 200 UP이고, 기본 redis 헬스 대신 bookingRedis가 들어 있다")
    void actuatorHealthUp() throws Exception {
        MockMvc mockMvc = MockMvcBuilders.webAppContextSetup(context).build();

        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
        assertThat(healthEndpoint.healthForPath("bookingRedis").getStatus()).isEqualTo(Status.UP);
        assertThat(healthEndpoint.healthForPath("redis")).isNull();
    }

    @Test
    @DisplayName("DEGRADED는 UP보다 앞서고 DOWN보다 뒤이며 HTTP 200, DOWN·OUT_OF_SERVICE는 그대로 503")
    void degradedStatusPolicy() {
        Status degraded = new Status("DEGRADED");

        assertThat(statusAggregator.getAggregateStatus(Status.UP, degraded)).isEqualTo(degraded);
        assertThat(statusAggregator.getAggregateStatus(Status.DOWN, degraded)).isEqualTo(Status.DOWN);
        assertThat(httpCodeStatusMapper.getStatusCode(degraded)).isEqualTo(200);
        assertThat(httpCodeStatusMapper.getStatusCode(Status.UP)).isEqualTo(200);
        assertThat(httpCodeStatusMapper.getStatusCode(Status.DOWN)).isEqualTo(503);
        assertThat(httpCodeStatusMapper.getStatusCode(Status.OUT_OF_SERVICE)).isEqualTo(503);
    }

    private static int closedPort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
