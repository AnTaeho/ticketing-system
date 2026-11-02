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

// API 예외 처리기가 사용자 식별·헤더·본문 검증 실패를 어떻게 답하는지 확인하는 시험용 컨트롤러
@TestConfiguration(proxyBeanMethods = false)
public class ProbeConfig {

    @RestController
    @RequestMapping("/test/probe")
    static class ProbeController {

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
