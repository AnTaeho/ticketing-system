package com.example.ticketing.global.chaos;

import org.springframework.stereotype.Component;

@Component
public class ChaosState {

    public enum RedisMode { NONE, DELAY, BLOCK }

    private volatile boolean hikariConstrained = false;
    private volatile int hikariMaxPoolSize = -1;
    private volatile int originalMaxPoolSize = -1;
    private volatile RedisMode redisMode = RedisMode.NONE;
    private volatile long redisDelayMs = 0;
    private volatile boolean kafkaPaused = false;

    public boolean isHikariConstrained()    { return hikariConstrained; }
    public int getHikariMaxPoolSize()       { return hikariMaxPoolSize; }
    public int getOriginalMaxPoolSize()     { return originalMaxPoolSize; }
    public RedisMode getRedisMode()         { return redisMode; }
    public long getRedisDelayMs()           { return redisDelayMs; }
    public boolean isKafkaPaused()          { return kafkaPaused; }

    public void setHikariConstrained(boolean v)  { this.hikariConstrained = v; }
    public void setHikariMaxPoolSize(int v)      { this.hikariMaxPoolSize = v; }
    public void setOriginalMaxPoolSize(int v)    { this.originalMaxPoolSize = v; }
    public void setRedisMode(RedisMode v)        { this.redisMode = v; }
    public void setRedisDelayMs(long v)          { this.redisDelayMs = v; }
    public void setKafkaPaused(boolean v)        { this.kafkaPaused = v; }

    public void resetHikari() {
        this.hikariConstrained = false;
        this.hikariMaxPoolSize = -1;
        this.originalMaxPoolSize = -1;
    }
}
