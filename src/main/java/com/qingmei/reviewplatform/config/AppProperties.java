package com.qingmei.reviewplatform.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

@Configuration
public class AppProperties {

    @Value("${app.storage-dir}")
    private String storageDir;

    @Value("${app.share-base-url}")
    private String shareBaseUrl;

    @Value("${app.cache-ttl-seconds}")
    private long cacheTtlSeconds;

    @Value("${app.share-default-hours}")
    private long shareDefaultHours;

    @Value("${app.auth-session-hours}")
    private long authSessionHours;

    @Value("${app.http-addr}")
    private String httpAddr;

    public String getStorageDir() {
        return storageDir;
    }

    public String getShareBaseUrl() {
        return shareBaseUrl;
    }

    public Duration getCacheTtl() {
        return Duration.ofSeconds(cacheTtlSeconds);
    }

    public Duration getShareDefaultTtl() {
        return Duration.ofHours(shareDefaultHours);
    }

    public Duration getAuthSessionTtl() {
        return Duration.ofHours(authSessionHours);
    }

    public int getServerPort() {
        String raw = httpAddr == null ? "8080" : httpAddr.trim();
        if (raw.startsWith(":")) {
            raw = raw.substring(1);
        }
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException ex) {
            return 8080;
        }
    }
}
