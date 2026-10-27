package com.example.ticketing.booking.ops.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.ticketing.booking.support.BookingFixtures;
import com.example.ticketing.booking.support.BookingIntegrationTest;
import com.example.ticketing.booking.web.OpsPageController;
import java.util.Arrays;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.AnnotatedBeanDefinitionReader;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

@BookingIntegrationTest
class OpsApiControllerTest {

    @Autowired
    private WebApplicationContext context;
    @Autowired
    private BookingFixtures fixtures;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).build();
    }

    @Test
    @DisplayName("로그인 없이 회차 집계를 JSON으로 돌려준다")
    void summaryShape() throws Exception {
        long pid = fixtures.newPerformance(1, 1, 3);

        mockMvc.perform(get("/api/ops/performances/{pid}/summary", pid))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.performance.performanceId").value(pid))
                .andExpect(jsonPath("$.performance.showTitle").isString())
                .andExpect(jsonPath("$.generatedAt").isString())
                .andExpect(jsonPath("$.seats.AVAILABLE").value(3))
                .andExpect(jsonPath("$.seats.HELD").value(0))
                .andExpect(jsonPath("$.holds.REFUNDING").value(0))
                .andExpect(jsonPath("$.payments.UNKNOWN").value(0))
                .andExpect(jsonPath("$.bookings.CANCELLED").value(0))
                .andExpect(jsonPath("$.outbox.TICKET_ISSUED.FAILED").value(0))
                .andExpect(jsonPath("$.outbox.REFUND_REQUESTED.PENDING").value(0))
                .andExpect(jsonPath("$.attention.failedTasks").isArray())
                .andExpect(jsonPath("$.attention.refundingHolds").isArray())
                .andExpect(jsonPath("$.attention.stalePayments").isArray())
                .andExpect(jsonPath("$.redisAvailable").value(true))
                .andExpect(jsonPath("$.queue.waiting").value(0))
                .andExpect(jsonPath("$.gateKeys.seat").value(0))
                .andExpect(jsonPath("$.counters['booking.hold.gate'].tags[0]").value("result"))
                .andExpect(jsonPath("$.counters['booking.hold.gate'].total").isNumber());
    }

    @Test
    @DisplayName("없는 회차는 404 PERFORMANCE_NOT_FOUND")
    void unknownPerformance() throws Exception {
        mockMvc.perform(get("/api/ops/performances/{pid}/summary", Long.MAX_VALUE - 7))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PERFORMANCE_NOT_FOUND"));
    }

    @ParameterizedTest(name = "{0} → {1}개")
    @CsvSource({"prod, 0", "local, 2", "test, 2"})
    @DisplayName("집계 API와 운영 화면 컨트롤러는 prod 프로필에서 등록되지 않는다")
    void registeredOnlyOutsideProd(String profile, int expected) {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles(profile);
        try (GenericApplicationContext probe = new GenericApplicationContext()) {
            probe.setEnvironment(environment);
            new AnnotatedBeanDefinitionReader(probe).register(OpsApiController.class, OpsPageController.class);

            // 새로 고치지 않아도 등록 단계에서 @Profile이 판정된다
            assertThat(Arrays.stream(probe.getBeanDefinitionNames())
                    .filter(name -> name.equals("opsApiController") || name.equals("opsPageController")))
                    .hasSize(expected);
        }
    }
}
