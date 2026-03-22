package com.aireview;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

@SpringBootApplication
@MapperScan("com.aireview.repository")
@EnableAsync
public class AiReviewApplication {

    public static void main(String[] args) {
        SpringApplication.run(AiReviewApplication.class, args);
    }
}
