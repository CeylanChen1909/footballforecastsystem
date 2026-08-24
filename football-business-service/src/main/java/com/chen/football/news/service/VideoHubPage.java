package com.chen.football.news.service;

import com.chen.football.news.entity.VideoHubItem;

import java.util.List;

public record VideoHubPage(List<VideoHubItem> items, long total, int page, int size) {
}
