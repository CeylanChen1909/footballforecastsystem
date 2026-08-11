package com.chen.football.news.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.chen.football.news.entity.VideoHubItem;

import java.util.List;
import java.util.Map;

public interface VideoHubService {
    List<VideoHubItem> listVideos(String keyword, String status);
    VideoHubPage listVideosPage(String keyword, String status, Integer page, Integer size);
    VideoHubItem getById(Long id);
    Map<String, Object> saveVideo(VideoHubItem item, Long userId, String username);
    Map<String, Object> deleteVideo(Long id, Long userId, String username);
    Map<String, Object> updateStatus(Long id, String status, Long userId, String username);
    List<VideoHubItem> listPublicVideos(String keyword, String leagueName, String platform, String videoType, Integer limit);
    Map<String, Object> saveDebugSeed(Long userId, String username);
}
