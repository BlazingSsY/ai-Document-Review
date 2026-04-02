package com.aireview;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
@MapperScan("com.aireview.repository")
public class AiReviewApplication {

    public static void main(String[] args) {
        SpringApplication.run(AiReviewApplication.class, args);
    }
}
