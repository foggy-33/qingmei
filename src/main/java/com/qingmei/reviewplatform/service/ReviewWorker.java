package com.qingmei.reviewplatform.service;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
@EnableScheduling
public class ReviewWorker {

    private final StringRedisTemplate redisTemplate;
    private final ReviewService reviewService;

    public ReviewWorker(StringRedisTemplate redisTemplate, ReviewService reviewService) {
        this.redisTemplate = redisTemplate;
        this.reviewService = reviewService;
    }

    @Scheduled(fixedDelay = 100)
    public void process() throws InterruptedException {
        String assetId = redisTemplate.opsForList().rightPop(ReviewService.QUEUE_REVIEW_JOBS, Duration.ofSeconds(2));
        if (assetId == null || assetId.isBlank()) {
            return;
        }

        reviewService.setReviewTaskStatus(assetId, "processing");
        Thread.sleep(1000);
        reviewService.setReviewTaskStatus(assetId, "ready");
    }
}
