package com.example.ticketing.booking.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import com.example.ticketing.booking.support.BookingIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

@BookingIntegrationTest
class BookingPageControllerTest {

    @Autowired
    private WebApplicationContext context;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).build();
    }

    @ParameterizedTest(name = "{0} → {1}")
    @CsvSource({
            "/booking/login, booking/login, /booking/js/login.js",
            "/booking/shows, booking/shows, /booking/js/shows.js",
            "/booking/shows/7, booking/show, /booking/js/show.js",
            "/booking/performances/9/queue, booking/queue, /booking/js/queue.js",
            "/booking/performances/9/seats, booking/seats, /booking/js/seats.js"
    })
    @DisplayName("각 화면 경로는 200과 해당 뷰를 돌려주고, 레이아웃의 공통 머리·스타일·스크립트가 함께 렌더링된다")
    void rendersPage(String path, String viewName, String pageScript) throws Exception {
        mockMvc.perform(get(path))
                .andExpect(status().isOk())
                .andExpect(view().name(viewName))
                .andExpect(content().contentTypeCompatibleWith("text/html"))
                .andExpect(content().string(containsString("<html lang=\"ko\"")))
                .andExpect(content().string(containsString("name=\"viewport\"")))
                .andExpect(content().string(containsString("href=\"/booking/css/booking.css\"")))
                .andExpect(content().string(containsString("src=\"/booking/js/api.js\"")))
                .andExpect(content().string(containsString("src=\"" + pageScript + "\"")))
                .andExpect(content().string(containsString("id=\"page-title\"")));
    }

    @Test
    @DisplayName("공연 상세는 경로의 공연 번호를 화면에 넘기고 뒤로 가기를 공연 목록에 건다")
    void showPassesShowId() throws Exception {
        mockMvc.perform(get("/booking/shows/7"))
                .andExpect(model().attribute("showId", 7L))
                .andExpect(content().string(containsString("data-show-id=\"7\"")))
                .andExpect(content().string(containsString("href=\"/booking/shows\"")));
    }

    @Test
    @DisplayName("대기열 화면은 경로의 회차 번호를 화면에 넘긴다")
    void queuePassesPerformanceId() throws Exception {
        mockMvc.perform(get("/booking/performances/9/queue"))
                .andExpect(model().attribute("performanceId", 9L))
                .andExpect(content().string(containsString("data-performance-id=\"9\"")));
    }

    @Test
    @DisplayName("좌석 화면은 회차 번호와 최대 좌석 수를 넘기고 좌석 전용 스타일을 공통 스타일 뒤에 붙인다")
    void seatsPassesPerformanceIdAndStyle() throws Exception {
        String html = mockMvc.perform(get("/booking/performances/9/seats").param("show", "3"))
                .andExpect(status().isOk())
                .andExpect(model().attribute("performanceId", 9L))
                .andExpect(model().attribute("maxSeats", 4))
                .andExpect(content().string(containsString("data-performance-id=\"9\"")))
                .andExpect(content().string(containsString("data-max-seats=\"4\"")))
                .andExpect(content().string(containsString("id=\"seat-grid\"")))
                .andExpect(content().string(containsString("id=\"pick-submit\"")))
                .andReturn().getResponse().getContentAsString();
        assertThat(html.indexOf("href=\"/booking/css/seats.css\""))
                .isGreaterThan(html.indexOf("href=\"/booking/css/booking.css\""));
    }

    @Test
    @DisplayName("/booking은 공연 목록으로 보낸다")
    void homeRedirectsToShows() throws Exception {
        mockMvc.perform(get("/booking"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/booking/shows"));
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource({
            "/booking/css/booking.css",
            "/booking/js/api.js",
            "/booking/js/login.js",
            "/booking/js/shows.js",
            "/booking/js/show.js",
            "/booking/js/queue.js",
            "/booking/js/seats.js",
            "/booking/css/seats.css"
    })
    @DisplayName("화면이 쓰는 정적 파일이 제공된다")
    void servesStaticFiles(String path) throws Exception {
        mockMvc.perform(get(path)).andExpect(status().isOk());
    }
}
