package com.chen.football.news.service;

import com.chen.football.news.entity.NewsArticle;

import java.util.List;

public record NewsAdminPage(List<NewsArticle> items, long total, int page, int size) {
}
