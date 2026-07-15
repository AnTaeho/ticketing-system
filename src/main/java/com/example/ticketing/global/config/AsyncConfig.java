package com.example.ticketing.global.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

@Configuration
public class AsyncConfig {

    private static final int CORE_POOL_SIZE = 50;
    private static final int MAX_POOL_SIZE = 200;
    private static final int QUEUE_CAPACITY = 200;
    private static final int KEEP_ALIVE_SECONDS = 60;

    @Bean(name = "waitingRoomTaskExecutor")
    public ThreadPoolTaskExecutor waitingRoomTaskExecutor() {
        return createExecutor(CORE_POOL_SIZE, MAX_POOL_SIZE, QUEUE_CAPACITY, "waitingroom-async-");
    }

    @Bean(name = "outboxTaskExecutor")
    public ThreadPoolTaskExecutor outboxTaskExecutor() {
        return createExecutor(2, 10, 100, "outbox-async-");
    }

    private ThreadPoolTaskExecutor createExecutor(int corePoolSize, int maxPoolSize,
                                                   int queueCapacity, String threadNamePrefix) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(corePoolSize);
        executor.setMaxPoolSize(maxPoolSize);
        executor.setQueueCapacity(queueCapacity);
        executor.setKeepAliveSeconds(KEEP_ALIVE_SECONDS);
        executor.setThreadNamePrefix(threadNamePrefix);
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.initialize();
        return executor;
    }
}
