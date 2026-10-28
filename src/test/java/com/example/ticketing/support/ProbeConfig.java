package com.example.ticketing.support;

import com.example.ticketing.common.error.ApiException;
import com.example.ticketing.common.error.ErrorCode;
import com.example.ticketing.common.web.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import java.util.Map;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// 예매 컨트롤러가 생기기 전 예외 처리 순서를 확인하려고 booking 패키지 안에 둔 시험용 컨트롤러
@TestConfiguration(proxyBeanMethods = false)
public class ProbeConfig {

    @RestController
    @RequestMapping("/test/booking-probe")
    static class BookingProbeController {

        @GetMapping("/me")
        Map<String, Long> me(@CurrentUser Long userId) {
            return Map.of("userId", userId);
        }

        @GetMapping("/seat-taken")
        void seatTaken(@CurrentUser Long userId) {
            throw new ApiException(ErrorCode.SEAT_TAKEN, Map.of("seatIds", List.of(7L, 8L)));
        }

        @PostMapping("/required-header")
        void requiredHeader(@CurrentUser Long userId, @RequestHeader("X-Probe") String probe) {
        }

        @PostMapping("/validate")
        void validate(@CurrentUser Long userId, @Valid @RequestBody ProbeRequest request) {
        }

        @GetMapping("/boom")
        void boom(@CurrentUser Long userId) {
            throw new IllegalStateException("boom");
        }
    }

    record ProbeRequest(@NotBlank String nickname) {
    }
}
