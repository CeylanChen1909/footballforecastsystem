package com.chen.football.crawler;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(scanBasePackages = {"com.chen.football.crawler", "com.chen.football.common"})
@EnableScheduling
public class FootballCrawlerApplication {

    public static void main(String[] args) {
        SpringApplication.run(FootballCrawlerApplication.class, args);
    }
}
