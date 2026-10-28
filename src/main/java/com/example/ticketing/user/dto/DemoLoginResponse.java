package com.example.ticketing.user.dto;

public record DemoLoginResponse(Long userId, String nickname) {

    public static DemoLoginResponse from(DemoLoginResult result) {
        return new DemoLoginResponse(result.userId(), result.nickname());
    }
}
