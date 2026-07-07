package com.aireview;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
@MapperScan({
        "com.aireview.modelconfig.repository",
        "com.aireview.review.chunk.repository",
        "com.aireview.review.sar.repository",
        "com.aireview.rule.repository",
        "com.aireview.scenario.repository",
        "com.aireview.user.repository"
})
public class AiReviewApplication {

    public static void main(String[] args) {
        SpringApplication.run(AiReviewApplication.class, args);
    }
}
