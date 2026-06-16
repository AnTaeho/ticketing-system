package com.example.ticketing.global.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

@Configuration
public class    AsyncConfig {

    private static final int CORE_POOL_SIZE = 50;
    private static final int MAX_POOL_SIZE = 200;
    private static final int QUEUE_CAPACITY = 200;
    private static final int KEEP_ALIVE_SECONDS = 60;

    @Bean(name = "waitingRoomTaskExecutor")
    public ThreadPoolTaskExecutor waitingRoomTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(CORE_POOL_SIZE);
        executor.setMaxPoolSize(MAX_POOL_SIZE);
        executor.setQueueCapacity(QUEUE_CAPACITY);
        executor.setKeepAliveSeconds(KEEP_ALIVE_SECONDS);
        executor.setThreadNamePrefix("waitingroom-async-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.initialize();
        return executor;
    }
}
