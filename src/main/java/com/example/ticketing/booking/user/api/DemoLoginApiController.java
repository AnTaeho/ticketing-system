package com.example.ticketing.booking.user.api;

import com.example.ticketing.booking.common.CurrentUserArgumentResolver;
import com.example.ticketing.booking.user.DemoLoginService;
import com.example.ticketing.booking.user.dto.DemoLoginResult;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class DemoLoginApiController {

    private final DemoLoginService demoLoginService;

    @PostMapping("/api/demo/login")
    public ResponseEntity<DemoLoginResult> login(@RequestBody DemoLoginRequest request) {
        DemoLoginResult result = demoLoginService.login(request.nickname());
        ResponseCookie cookie = ResponseCookie.from(CurrentUserArgumentResolver.USER_ID_COOKIE,
                        String.valueOf(result.userId()))
                .path("/")
                .sameSite("Lax")
                .httpOnly(true)
                .build();
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, cookie.toString())
                .body(result);
    }
}
