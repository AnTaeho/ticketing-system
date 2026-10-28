package com.example.ticketing.show.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.ticketing.support.Fixtures;
import com.example.ticketing.support.IntegrationTest;
import com.example.ticketing.support.TestMvc;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;

@IntegrationTest
class ShowApiControllerTest {

    private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

    @Autowired
    private TestMvc testMvc;
    @Autowired
    private Fixtures fixtures;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = testMvc.mockMvc();
    }

    @Test
    @DisplayName("관리 API로 공연·회차를 만들면 조회 API가 회차와 state를 돌려준다")
    void createThenQuery() throws Exception {
        long showId = createShow("""
                {"title":"API 공연","venueName":"API 홀","description":"설명"}""");
        LocalDateTime now = fixtures.dbNow().withNano(0);
        LocalDateTime openAt = now.minusMinutes(1);
        LocalDateTime startsAt = now.plusDays(1);

        MvcResult created = mockMvc.perform(post("/api/admin/performances")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"showId":%d,"startsAt":"%s","openAt":"%s","sections":2,"rows":3,"seatsPerRow":4}"""
                                .formatted(showId, startsAt.format(ISO), openAt.format(ISO))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.performanceId").isNumber())
                .andExpect(jsonPath("$.seatCount").value(24))
                .andReturn();
        long pid = testMvc.body(created).get("performanceId").asLong();
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM seat WHERE performance_id = ?",
                Long.class, pid)).isEqualTo(24);

        mockMvc.perform(get("/api/shows/{showId}", showId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(showId))
                .andExpect(jsonPath("$.title").value("API 공연"))
                .andExpect(jsonPath("$.venueName").value("API 홀"))
                .andExpect(jsonPath("$.description").value("설명"))
                .andExpect(jsonPath("$.performances.length()").value(1))
                .andExpect(jsonPath("$.performances[0].id").value(pid))
                .andExpect(jsonPath("$.performances[0].startsAt").value(startsAt.format(ISO)))
                .andExpect(jsonPath("$.performances[0].openAt").value(openAt.format(ISO)))
                .andExpect(jsonPath("$.performances[0].state").value("OPEN"));

        JsonNode shows = testMvc.body(mockMvc.perform(get("/api/shows"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andReturn());
        JsonNode summary = null;
        for (JsonNode show : shows) {
            if (show.get("id").asLong() == showId) {
                summary = show;
            }
        }
        assertThat(summary).isNotNull();
        assertThat(summary.get("title").asString()).isEqualTo("API 공연");
        assertThat(summary.get("venueName").asString()).isEqualTo("API 홀");
        assertThat(summary.get("nextPerformance").get("id").asLong()).isEqualTo(pid);
        assertThat(summary.get("nextPerformance").get("state").asString()).isEqualTo("OPEN");
        assertThat(summary.get("nextPerformance").get("startsAt").asString()).isEqualTo(startsAt.format(ISO));
    }

    @Test
    @DisplayName("없는 공연을 조회하거나 그 공연에 회차를 만들면 404로 답한다")
    void showNotFound() throws Exception {
        mockMvc.perform(get("/api/shows/{showId}", Long.MAX_VALUE))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("SHOW_NOT_FOUND"));

        LocalDateTime now = fixtures.dbNow().withNano(0);
        mockMvc.perform(post("/api/admin/performances")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(performanceJson(Long.MAX_VALUE, now.plusDays(1), now, 1, 1, 1)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("SHOW_NOT_FOUND"));
    }

    @Test
    @DisplayName("잘못된 입력은 400 INVALID_REQUEST로 거절한다")
    void invalidRequests() throws Exception {
        long showId = fixtures.newShow();
        LocalDateTime now = fixtures.dbNow().withNano(0);

        expectInvalid(post("/api/admin/shows"), """
                {"title":"","venueName":"홀"}""");
        expectInvalid(post("/api/admin/performances"), """
                {"startsAt":"%s","openAt":"%s","sections":1,"rows":1,"seatsPerRow":1}"""
                .formatted(now.plusDays(1).format(ISO), now.format(ISO)));
        expectInvalid(post("/api/admin/performances"), performanceJson(showId, now, now.plusMinutes(1), 1, 1, 1));
        expectInvalid(post("/api/admin/performances"), performanceJson(showId, now.plusDays(1), now, 27, 1, 1));
        expectInvalid(post("/api/admin/performances"), """
                {"showId":%d,"startsAt":"내일","openAt":"%s","sections":1,"rows":1,"seatsPerRow":1}"""
                .formatted(showId, now.format(ISO)));
        mockMvc.perform(get("/api/shows/abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    private long createShow(String json) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/admin/shows")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.showId").isNumber())
                .andReturn();
        return testMvc.body(result).get("showId").asLong();
    }

    private void expectInvalid(
            MockHttpServletRequestBuilder request, String json)
            throws Exception {
        mockMvc.perform(request.contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.error").doesNotExist());
    }

    private static String performanceJson(long showId, LocalDateTime startsAt, LocalDateTime openAt,
                                          int sections, int rows, int seatsPerRow) {
        return """
                {"showId":%d,"startsAt":"%s","openAt":"%s","sections":%d,"rows":%d,"seatsPerRow":%d}"""
                .formatted(showId, startsAt.format(ISO), openAt.format(ISO), sections, rows, seatsPerRow);
    }
}
