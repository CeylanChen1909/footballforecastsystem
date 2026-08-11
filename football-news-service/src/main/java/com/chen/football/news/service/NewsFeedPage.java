package com.chen.football.news.service;

import com.chen.football.news.dto.NewsArticleSummaryDTO;

import java.util.List;

public record NewsFeedPage(List<NewsArticleSummaryDTO> items, long total, int page, int size) {
}
