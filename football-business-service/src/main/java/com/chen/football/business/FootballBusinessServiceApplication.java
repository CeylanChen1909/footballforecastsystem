package com.chen.football.business;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableDiscoveryClient
@EnableScheduling
@MapperScan({
        "com.chen.football.match.mapper",
        "com.chen.football.news.mapper",
        "com.chen.football.prediction.mapper",
        "com.chen.football.datasync.mapper",
        "com.chen.football.crawler.mapper"
})
@ComponentScan(
        basePackages = {
                "com.chen.football.match",
                "com.chen.football.team",
                "com.chen.football.news",
                "com.chen.football.prediction",
                "com.chen.football.datasync",
                "com.chen.football.crawler",
                "com.chen.football.agent",
                "com.chen.football.analytics",
                "com.chen.football.search",
                "com.chen.football.card",
                "com.chen.football.common"
        },
        excludeFilters = @ComponentScan.Filter(
                type = FilterType.REGEX,
                pattern = "com\\.chen\\.football\\.(match|team|news|prediction|datasync|crawler)\\..*Application"
        )
)
public class FootballBusinessServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(FootballBusinessServiceApplication.class, args);
    }
}
