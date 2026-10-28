package com.example.ticketing.common.web;

import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

// prod가 아니면 시연 장치(결제 결과 고르기)를 켠다
@Component
public class DemoMode {

    private final boolean enabled;

    public DemoMode(Environment environment) {
        this.enabled = !environment.acceptsProfiles(Profiles.of("prod"));
    }

    public boolean enabled() {
        return enabled;
    }
}
