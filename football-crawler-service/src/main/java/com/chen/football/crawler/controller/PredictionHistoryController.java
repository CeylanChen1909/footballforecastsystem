package com.chen.football.crawler.controller;

import com.chen.football.common.dto.ApiResponse;
import com.chen.football.crawler.service.PredictionHistoryService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/predictions")
@RequiredArgsConstructor
public class PredictionHistoryController {

    private final PredictionHistoryService predictionHistoryService;

    private Long currentUserId() {
        return 1L;
    }

    @GetMapping("/history")
    public ApiResponse<List<Map<String, Object>>> history(@RequestParam(name = "limit", defaultValue = "20") int limit) {
        return ApiResponse.ok(predictionHistoryService.list(currentUserId(), limit));
    }
}
