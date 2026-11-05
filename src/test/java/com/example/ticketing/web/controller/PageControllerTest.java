package com.example.ticketing.web.controller;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import com.example.ticketing.common.config.TicketingProperties;
import com.example.ticketing.common.error.ApiExceptionHandler;
import com.example.ticketing.common.web.DemoMode;
import com.example.ticketing.support.IntegrationTest;
import com.example.ticketing.support.TestMvc;
import com.example.ticketing.web.PageExceptionHandler;
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

@IntegrationTest
class PageControllerTest {

    @Autowired
    private WebApplicationContext context;
    @Autowired
    private TestMvc testMvc;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = testMvc.mockMvc();
    }

    @ParameterizedTest(name = "{0} 경로는 {1} 뷰를 그린다")
    @CsvSource({
            "/booking/login, booking/login",
            "/booking/shows, booking/shows",
            "/booking/shows/7, booking/show",
            "/booking/performances/9/seats, booking/seats",
            "/booking/holds/5/checkout, booking/checkout",
            "/booking/bookings, booking/bookings"
    })
    @DisplayName("각 화면 경로는 200으로 해당 뷰를 그린다")
    void rendersPage(String path, String viewName) throws Exception {
        mockMvc.perform(get(path))
                .andExpect(status().isOk())
                .andExpect(view().name(viewName))
                .andExpect(content().contentTypeCompatibleWith("text/html"));
    }

    @Test
    @DisplayName("화면은 경로의 번호와 좌석 수·시연 여부를 모델로 넘긴다")
    void passesModel() throws Exception {
        mockMvc.perform(get("/booking/shows/7")).andExpect(model().attribute("showId", 7L));
        mockMvc.perform(get("/booking/performances/9/seats"))
                .andExpect(model().attribute("performanceId", 9L))
                .andExpect(model().attribute("maxSeats", 4));
        mockMvc.perform(get("/booking/holds/5/checkout"))
                .andExpect(model().attribute("holdId", 5L))
                .andExpect(model().attribute("demoPg", true))
                .andExpect(content().string(containsString("name=\"scenario\" value=\"SLOW_SUCCESS\"")));
    }

    @Test
    @DisplayName("운영(prod)에서는 결제 화면에 시연 상자를 그리지 않는다")
    void checkoutHidesDemoBoxInProd() throws Exception {
        MockEnvironment prod = new MockEnvironment();
        prod.setActiveProfiles("prod");
        ThymeleafViewResolver resolver = new ThymeleafViewResolver();
        resolver.setTemplateEngine(context.getBean(ISpringTemplateEngine.class));
        resolver.setCharacterEncoding("UTF-8");
        MockMvc prodMvc = MockMvcBuilders
                .standaloneSetup(new PageController(context.getBean(TicketingProperties.class), new DemoMode(prod)))
                .setViewResolvers(resolver)
                .build();

        prodMvc.perform(get("/booking/holds/5/checkout"))
                .andExpect(status().isOk())
                .andExpect(model().attribute("demoPg", false))
                .andExpect(content().string(containsString("data-demo-pg=\"false\"")))
                .andExpect(content().string(containsString("id=\"co-pay\"")))
                .andExpect(content().string(not(containsString("name=\"scenario\""))));
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource({
            "/booking/shows/abc",
            "/booking/performances/1.5/seats",
            "/booking/holds/abc/checkout"
    })
    @DisplayName("화면 경로의 번호가 숫자가 아니면 404 오류 화면을 보여 준다")
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
    @DisplayName("없는 화면 경로는 어떤 처리기에도 잡히지 않고 404로 끝난다")
    void unknownPagePathIsNotFound() throws Exception {
        mockMvc.perform(get("/booking/nope"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("화면 컨트롤러의 다른 예외도 예매 JSON 처리기보다 먼저 잡혀 500 오류 화면이 된다")
    void unexpectedPageFailureShowsErrorPage() throws Exception {
        ThymeleafViewResolver resolver = new ThymeleafViewResolver();
        resolver.setTemplateEngine(context.getBean(ISpringTemplateEngine.class));
        resolver.setCharacterEncoding("UTF-8");
        PageController exploding = new PageController(context.getBean(TicketingProperties.class),
                new DemoMode(new MockEnvironment())) {
            @Override
            public String shows() {
                throw new IllegalStateException("boom");
            }
        };
        MockMvc failingMvc = MockMvcBuilders.standaloneSetup(exploding)
                .setControllerAdvice(new ApiExceptionHandler(), new PageExceptionHandler())
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
    @DisplayName("첫 화면 경로(/booking)는 공연 목록으로 보낸다")
    void homeRedirectsToShows() throws Exception {
        mockMvc.perform(get("/booking"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/booking/shows"));
    }
}
