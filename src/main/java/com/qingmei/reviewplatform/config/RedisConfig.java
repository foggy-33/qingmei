package com.qingmei.reviewplatform.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;

@Configuration
public class RedisConfig {

    @Value("${REDIS_ADDR:localhost:6379}")
    private String redisAddr;

    @Value("${REDIS_PASSWORD:}")
    private String redisPassword;

    @Value("${REDIS_DB:0}")
    private int redisDb;

    @Bean
    public LettuceConnectionFactory redisConnectionFactory() {
        String[] hostPort = redisAddr.split(":", 2);
        String host = hostPort[0];
        int port = hostPort.length > 1 ? Integer.parseInt(hostPort[1]) : 6379;

        RedisStandaloneConfiguration conf = new RedisStandaloneConfiguration(host, port);
        conf.setDatabase(redisDb);
        if (redisPassword != null && !redisPassword.isBlank()) {
            conf.setPassword(redisPassword);
        }
        return new LettuceConnectionFactory(conf);
    }
}
