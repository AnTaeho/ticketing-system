package com.example.ticketing.common.error;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.ticketing.booking.controller.BookingApiController;
import com.example.ticketing.fakepg.controller.FakePgController;
import com.example.ticketing.hold.controller.HoldApiController;
import com.example.ticketing.ops.controller.OpsApiController;
import com.example.ticketing.payment.controller.PaymentApiController;
import com.example.ticketing.queue.controller.QueueApiController;
import com.example.ticketing.seat.controller.SeatMapApiController;
import com.example.ticketing.show.controller.ShowAdminApiController;
import com.example.ticketing.show.controller.ShowApiController;
import com.example.ticketing.support.IntegrationTest;
import com.example.ticketing.support.ProbeConfig;
import com.example.ticketing.support.TestMvc;
import com.example.ticketing.user.controller.DemoLoginApiController;
import com.example.ticketing.web.PageExceptionHandler;
import com.example.ticketing.web.controller.OpsPageController;
import com.example.ticketing.web.controller.PageController;
import jakarta.servlet.http.Cookie;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.util.ClassUtils;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.method.ControllerAdviceBean;

@IntegrationTest
@Import(ProbeConfig.class)
class ApiExceptionHandlerTest {

    private static final String PROBE = "/test/booking-probe";

    @Autowired
    private WebApplicationContext context;
    @Autowired
    private TestMvc testMvc;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = testMvc.mockMvc();
    }

    @Test
    @DisplayName("사용자 정보가 없으면 예매 처리기가 401로 답한다")
    void loginRequired() throws Exception {
        mockMvc.perform(get(PROBE + "/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("LOGIN_REQUIRED"))
                .andExpect(jsonPath("$.message").value(ErrorCode.LOGIN_REQUIRED.getDefaultMessage()))
                .andExpect(jsonPath("$.detail").isMap())
                .andExpect(jsonPath("$.error").doesNotExist());
    }

    @Test
    @DisplayName("숫자가 아닌 X-User-Id는 로그인 안 한 것으로 본다")
    void invalidUserIdHeader() throws Exception {
        mockMvc.perform(get(PROBE + "/me").header(TestMvc.USER, "abc"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("LOGIN_REQUIRED"));
    }

    @Test
    @DisplayName("사용자 헤더(X-User-Id)가 쿠키(bk_uid)보다 먼저 쓰인다")
    void headerBeforeCookie() throws Exception {
        mockMvc.perform(get(PROBE + "/me").header(TestMvc.USER, "11").cookie(new Cookie("bk_uid", "22")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(11));
    }

    @Test
    @DisplayName("헤더가 없으면 bk_uid 쿠키로 사용자를 찾는다")
    void cookieFallback() throws Exception {
        mockMvc.perform(get(PROBE + "/me").cookie(new Cookie("bk_uid", "22")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(22));
    }

    @Test
    @DisplayName("예매 컨트롤러가 ApiException을 던지면 코드에 맞는 상태로 답한다")
    void bookingException() throws Exception {
        mockMvc.perform(get(PROBE + "/seat-taken").header(TestMvc.USER, "1"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SEAT_TAKEN"))
                .andExpect(jsonPath("$.message").value(ErrorCode.SEAT_TAKEN.getDefaultMessage()))
                .andExpect(jsonPath("$.detail.seatIds[0]").value(7))
                .andExpect(jsonPath("$.detail.seatIds[1]").value(8))
                .andExpect(jsonPath("$.error").doesNotExist());
    }

    @Test
    @DisplayName("필수 헤더가 없으면 빠진 헤더 이름을 담아 400으로 답한다")
    void missingRequiredHeader() throws Exception {
        mockMvc.perform(post(PROBE + "/required-header").header(TestMvc.USER, "1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.detail.header").value("X-Probe"))
                .andExpect(jsonPath("$.error").doesNotExist());
    }

    @Test
    @DisplayName("요청 본문 검증이 실패하면 틀린 필드를 담아 400으로 답한다")
    void validationFailure() throws Exception {
        mockMvc.perform(post(PROBE + "/validate").header(TestMvc.USER, "1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nickname\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.detail.fields.nickname").exists())
                .andExpect(jsonPath("$.error").doesNotExist());
    }

    @Test
    @DisplayName("예매 처리기가 다루지 않는 예외는 잡지 않고 넘겨 Boot 기본 오류 처리에 맡긴다")
    void unhandledIsNotCaught() {
        assertThatThrownBy(() -> mockMvc.perform(get(PROBE + "/boom").header(TestMvc.USER, "1")))
                .hasRootCauseInstanceOf(IllegalStateException.class)
                .hasRootCauseMessage("boom");
    }

    @Test
    @DisplayName("예매 처리기는 가짜 PG를 포함한 모든 @RestController를 맡고 화면 컨트롤러는 화면 처리기만 맡는다")
    void adviceScope() {
        Map<Class<?>, ControllerAdviceBean> advices = ControllerAdviceBean.findAnnotatedBeans(context).stream()
                .collect(Collectors.toMap(ControllerAdviceBean::getBeanType, advice -> advice));
        ControllerAdviceBean api = advices.get(ApiExceptionHandler.class);
        ControllerAdviceBean page = advices.get(PageExceptionHandler.class);
        List<Class<?>> restControllers = context.getBeansWithAnnotation(RestController.class).values().stream()
                .<Class<?>>map(ClassUtils::getUserClass)
                .toList();
        List<Class<?>> pageControllers = List.of(PageController.class, OpsPageController.class);

        assertThat(restControllers).contains(BookingApiController.class, DemoLoginApiController.class,
                FakePgController.class, HoldApiController.class, OpsApiController.class, PaymentApiController.class,
                QueueApiController.class, SeatMapApiController.class, ShowAdminApiController.class,
                ShowApiController.class);
        assertThat(restControllers).allSatisfy(type -> {
            assertThat(api.isApplicableToBeanType(type)).as(type.getName()).isTrue();
            assertThat(page.isApplicableToBeanType(type)).as(type.getName()).isFalse();
        });
        assertThat(pageControllers).allSatisfy(type -> {
            assertThat(api.isApplicableToBeanType(type)).as(type.getName()).isFalse();
            assertThat(page.isApplicableToBeanType(type)).as(type.getName()).isTrue();
        });
    }
}
