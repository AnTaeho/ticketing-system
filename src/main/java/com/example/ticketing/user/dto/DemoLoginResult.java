package com.example.ticketing.user.dto;

import com.example.ticketing.user.domain.AppUser;

public record DemoLoginResult(Long userId, String nickname) {

    public static DemoLoginResult from(AppUser user) {
        return new DemoLoginResult(user.getId(), user.getNickname());
    }
}
