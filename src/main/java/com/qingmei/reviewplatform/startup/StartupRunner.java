package com.qingmei.reviewplatform.startup;

import com.qingmei.reviewplatform.service.ReviewService;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Component
public class StartupRunner implements ApplicationRunner {

    private final ReviewService reviewService;

    public StartupRunner(ReviewService reviewService) {
        this.reviewService = reviewService;
    }

    @Override
    public void run(ApplicationArguments args) {
        reviewService.ensureStorageReady();
        reviewService.migrate();
    }
}
