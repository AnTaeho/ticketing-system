package com.example.ticketing.fakepg.config;

import com.example.ticketing.common.config.TicketingProperties;
import com.example.ticketing.fakepg.domain.FakePgDecider;
import com.example.ticketing.fakepg.service.FakePgSleeper;
import java.util.Random;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class FakePgConfig {

    @Bean
    public FakePgDecider fakePgDecider(TicketingProperties properties) {
        return new FakePgDecider(properties.fakePg(), new Random());
    }

    @Bean
    public FakePgSleeper fakePgSleeper() {
        return Thread::sleep;
    }
}
