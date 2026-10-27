package com.example.ticketing.booking.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import com.example.ticketing.booking.BookingProperties;
import com.example.ticketing.booking.common.BookingExceptionHandler;
import com.example.ticketing.booking.support.BookingIntegrationTest;
import com.example.ticketing.global.exception.GlobalExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.thymeleaf.spring6.ISpringTemplateEngine;
import org.thymeleaf.spring6.view.ThymeleafViewResolver;

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
            "/booking/performances/9/seats, booking/seats, /booking/js/seats.js",
            "/booking/holds/5/checkout, booking/checkout, /booking/js/checkout.js",
            "/booking/bookings, booking/bookings, /booking/js/bookings.js",
            "/booking/ops/performances/9, booking/ops, /booking/js/ops.js"
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
    @DisplayName("공연 상세는 경로의 공연 번호를 화면에 넘기고 뒤로 가기를 공연 목록에 걸며, 없는 공연이면 보일 안내와 공연 목록 단추를 둔다")
    void showPassesShowId() throws Exception {
        mockMvc.perform(get("/booking/shows/7"))
                .andExpect(model().attribute("showId", 7L))
                .andExpect(content().string(containsString("data-show-id=\"7\"")))
                .andExpect(content().string(containsString("href=\"/booking/shows\"")))
                .andExpect(content().string(containsString("id=\"show-missing\"")))
                .andExpect(content().string(containsString("찾을 수 없는 공연이에요")));
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
    @DisplayName("결제 화면은 선점 번호를 넘기고, prod가 아니면 결제 상황을 고르는 시연 상자를 그린다")
    void checkoutPassesHoldIdAndDemoBox() throws Exception {
        String html = mockMvc.perform(get("/booking/holds/5/checkout").param("show", "3"))
                .andExpect(status().isOk())
                .andExpect(model().attribute("holdId", 5L))
                .andExpect(model().attribute("demoPg", true))
                .andExpect(content().string(containsString("data-hold-id=\"5\"")))
                .andExpect(content().string(containsString("data-demo-pg=\"true\"")))
                .andExpect(content().string(containsString("id=\"clock\"")))
                .andExpect(content().string(containsString("id=\"pay\"")))
                .andExpect(content().string(containsString("id=\"release\"")))
                .andExpect(content().string(containsString("name=\"scenario\" value=\"SLOW_SUCCESS\"")))
                .andReturn().getResponse().getContentAsString();
        assertThat(html.indexOf("href=\"/booking/css/checkout.css\""))
                .isGreaterThan(html.indexOf("href=\"/booking/css/booking.css\""));
        assertThat(html).doesNotContain("수수료");
    }

    @Test
    @DisplayName("prod에서는 결제 화면에 시연 상자를 그리지 않는다")
    void checkoutHidesDemoBoxInProd() throws Exception {
        MockEnvironment prod = new MockEnvironment();
        prod.setActiveProfiles("prod");
        ThymeleafViewResolver resolver = new ThymeleafViewResolver();
        resolver.setTemplateEngine(context.getBean(ISpringTemplateEngine.class));
        resolver.setCharacterEncoding("UTF-8");
        MockMvc prodMvc = MockMvcBuilders
                .standaloneSetup(new BookingPageController(context.getBean(BookingProperties.class), prod))
                .setViewResolvers(resolver)
                .build();

        prodMvc.perform(get("/booking/holds/5/checkout"))
                .andExpect(status().isOk())
                .andExpect(model().attribute("demoPg", false))
                .andExpect(content().string(containsString("data-demo-pg=\"false\"")))
                .andExpect(content().string(containsString("id=\"pay\"")))
                .andExpect(content().string(not(containsString("name=\"scenario\""))));
    }

    @Test
    @DisplayName("공연 목록 머리에 내 예매로 가는 링크를 두고, 다른 화면 머리에는 두지 않는다")
    void showsLinksToBookings() throws Exception {
        mockMvc.perform(get("/booking/shows"))
                .andExpect(content().string(containsString("id=\"page-link\"")))
                .andExpect(content().string(containsString("href=\"/booking/bookings\"")))
                .andExpect(content().string(containsString(">내 예매</a>")));
        mockMvc.perform(get("/booking/shows/7"))
                .andExpect(content().string(not(containsString("id=\"page-link\""))));
    }

    @Test
    @DisplayName("내 예매는 뒤로 가기를 공연 목록에 걸고, 예매가 없을 때 공연 보러 가기 단추를 둔다")
    void bookingsPage() throws Exception {
        String html = mockMvc.perform(get("/booking/bookings"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("id=\"bookings\"")))
                .andExpect(content().string(containsString("아직 예매한 공연이 없어요")))
                .andExpect(content().string(containsString("공연 보러 가기")))
                .andReturn().getResponse().getContentAsString();
        assertThat(html).contains("id=\"page-back\" href=\"/booking/shows\"");
        assertThat(html.indexOf("href=\"/booking/css/bookings.css\""))
                .isGreaterThan(html.indexOf("href=\"/booking/css/booking.css\""));
    }

    @Test
    @DisplayName("운영 현황은 경로의 회차 번호를 화면에 넘기고 넓은 화면 스타일을 붙인다")
    void opsPassesPerformanceId() throws Exception {
        mockMvc.perform(get("/booking/ops/performances/9"))
                .andExpect(model().attribute("performanceId", 9L))
                .andExpect(content().string(containsString("data-performance-id=\"9\"")))
                .andExpect(content().string(containsString("href=\"/booking/css/ops.css\"")))
                .andExpect(content().string(containsString("id=\"ops-grid\"")))
                .andExpect(content().string(containsString("Redis를 읽지 못했어요")));
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource({
            "/booking/shows/abc",
            "/booking/performances/x/queue",
            "/booking/performances/1.5/seats",
            "/booking/holds/abc/checkout",
            "/booking/ops/performances/abc"
    })
    @DisplayName("화면 경로의 번호가 숫자가 아니면 JSON 대신 404 오류 화면과 공연 목록 단추를 보여 준다")
    void badNumberInPagePathShowsErrorPage(String path) throws Exception {
        mockMvc.perform(get(path))
                .andExpect(status().isNotFound())
                .andExpect(view().name("booking/page-error"))
                .andExpect(content().contentTypeCompatibleWith("text/html"))
                .andExpect(content().string(containsString("찾을 수 없는 화면이에요")))
                .andExpect(content().string(containsString("class=\"btn btn-primary\" href=\"/booking/shows\">공연 목록</a>")))
                .andExpect(content().string(containsString("href=\"/booking/css/booking.css\"")));
    }

    @Test
    @DisplayName("API 경로의 잘못된 번호는 지금처럼 예매 JSON 400으로 답한다")
    void badNumberInApiPathStaysJson() throws Exception {
        mockMvc.perform(get("/api/shows/abc"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    @DisplayName("화면 컨트롤러의 다른 예외도 예매·전역 JSON 처리기보다 먼저 잡혀 500 오류 화면이 된다")
    void unexpectedPageFailureShowsErrorPage() throws Exception {
        ThymeleafViewResolver resolver = new ThymeleafViewResolver();
        resolver.setTemplateEngine(context.getBean(ISpringTemplateEngine.class));
        resolver.setCharacterEncoding("UTF-8");
        BookingPageController exploding = new BookingPageController(context.getBean(BookingProperties.class),
                new MockEnvironment()) {
            @Override
            public String shows() {
                throw new IllegalStateException("boom");
            }
        };
        MockMvc failingMvc = MockMvcBuilders.standaloneSetup(exploding)
                .setControllerAdvice(new GlobalExceptionHandler(), new BookingExceptionHandler(),
                        new BookingPageExceptionHandler())
                .setViewResolvers(resolver)
                .build();

        failingMvc.perform(get("/booking/shows"))
                .andExpect(status().isInternalServerError())
                .andExpect(view().name("booking/page-error"))
                .andExpect(content().string(containsString("화면을 열지 못했어요")))
                .andExpect(content().string(containsString(">공연 목록</a>")))
                .andExpect(content().string(not(containsString("boom"))));
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
            "/booking/css/seats.css",
            "/booking/js/checkout.js",
            "/booking/css/checkout.css",
            "/booking/js/bookings.js",
            "/booking/css/bookings.css",
            "/booking/js/ops.js",
            "/booking/css/ops.css"
    })
    @DisplayName("화면이 쓰는 정적 파일이 제공된다")
    void servesStaticFiles(String path) throws Exception {
        mockMvc.perform(get(path)).andExpect(status().isOk());
    }
}
