package com.chen.football.prediction;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.ComponentScan;

@SpringBootApplication
@MapperScan("com.chen.football.prediction.mapper")
@ComponentScan(basePackages = {
    "com.chen.football.prediction",
    "com.chen.football.common"
})
public class PredictionServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(PredictionServiceApplication.class, args);
    }
}
