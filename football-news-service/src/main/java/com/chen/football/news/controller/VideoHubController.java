package com.chen.football.news.controller;

import com.chen.football.common.dto.ApiResponse;
import com.chen.football.news.entity.VideoHubItem;
import com.chen.football.news.service.VideoHubService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/videos")
@RequiredArgsConstructor
public class VideoHubController {

    private final VideoHubService videoHubService;

    @GetMapping
    public ApiResponse<Map<String, Object>> list(@RequestParam(name = "keyword", required = false) String keyword,
                                                 @RequestParam(name = "leagueName", required = false) String leagueName,
                                                 @RequestParam(name = "platform", required = false) String platform,
                                                 @RequestParam(name = "videoType", required = false) String videoType,
                                                 @RequestParam(name = "limit", defaultValue = "20") Integer limit) {
        List<VideoHubItem> items = videoHubService.listPublicVideos(keyword, leagueName, platform, videoType, limit);
        return ApiResponse.ok(Map.of("items", items, "total", items.size()));
    }
}
