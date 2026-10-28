package com.example.ticketing.user.controller;

import com.example.ticketing.common.web.HttpNames;
import com.example.ticketing.user.dto.DemoLoginRequest;
import com.example.ticketing.user.dto.DemoLoginResponse;
import com.example.ticketing.user.dto.DemoLoginResult;
import com.example.ticketing.user.service.DemoLoginService;
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

    // 닉네임 길이는 화면에 같은 문장을 내려고 서비스가 검사한다
    @PostMapping("/api/demo/login")
    public ResponseEntity<DemoLoginResponse> login(@RequestBody DemoLoginRequest request) {
        DemoLoginResult result = demoLoginService.login(request.nickname());
        ResponseCookie cookie = ResponseCookie.from(HttpNames.USER_ID_COOKIE,
                        String.valueOf(result.userId()))
                .path("/")
                .sameSite("Lax")
                .httpOnly(true)
                .build();
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, cookie.toString())
                .body(DemoLoginResponse.from(result));
    }
}
