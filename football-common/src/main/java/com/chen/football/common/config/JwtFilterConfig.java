package com.chen.football.common.config;

import com.chen.football.common.filter.JwtFilter;
import com.chen.football.common.util.JwtUtil;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class JwtFilterConfig {

    @Bean
    public FilterRegistrationBean<JwtFilter> jwtFilterRegistration(JwtUtil jwtUtil) {
        FilterRegistrationBean<JwtFilter> bean = new FilterRegistrationBean<>();
        bean.setFilter(new JwtFilter(jwtUtil));
        bean.addUrlPatterns("/*");
        bean.setOrder(1);
        return bean;
    }
}
