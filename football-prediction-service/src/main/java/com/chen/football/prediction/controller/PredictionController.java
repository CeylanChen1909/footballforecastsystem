package com.chen.football.prediction.controller;

import com.chen.football.common.context.UserContext;
import com.chen.football.common.dto.ApiResponse;
import com.chen.football.common.dto.MatchPredictionRequest;
import com.chen.football.common.dto.MatchPredictionResponse;
import com.chen.football.prediction.entity.PredictionEntity;
import com.chen.football.prediction.service.PersistencePredictionService;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/predictions")
public class PredictionController {

    private final PersistencePredictionService predictionService;

    public PredictionController(PersistencePredictionService predictionService) {
        this.predictionService = predictionService;
    }

    @PostMapping("/match-result")
    public ApiResponse<MatchPredictionResponse> predict(@RequestBody MatchPredictionRequest req) {
        try {
            return ApiResponse.ok(predictionService.predictAndSave(req));
        } catch (Exception e) {
            return new ApiResponse<>(false, "预测计算失败: " + e.getMessage(), null);
        }
    }

    @GetMapping("/history")
    public ApiResponse<List<PredictionEntity>> history(@RequestParam(name = "limit", defaultValue = "20") int limit) {
        Long userId = UserContext.getUserId();
        try {
            int safeLimit = Math.max(1, Math.min(limit, 200));
            if (userId != null) {
                return ApiResponse.ok(predictionService.latestByUser(userId, safeLimit));
            }
            return ApiResponse.ok(predictionService.latest(safeLimit));
        } catch (Exception e) {
            return new ApiResponse<>(false, "获取预测历史失败: " + e.getMessage(), List.of());
        }
    }

    @GetMapping("/statistics")
    public ApiResponse<?> statistics() {
        Long userId = UserContext.getUserId();
        var stats = predictionService.getStatistics(userId);
        return ApiResponse.ok(stats);
    }
}
