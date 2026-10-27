package com.example.ticketing.booking.payment.fakepg;

import com.example.ticketing.booking.BookingProperties;
import java.util.Random;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class FakePgConfig {

    @Bean
    public FakePgDecider fakePgDecider(BookingProperties properties) {
        return new FakePgDecider(properties.fakePg(), new Random());
    }

    @Bean
    public FakePgSleeper fakePgSleeper() {
        return Thread::sleep;
    }
}
