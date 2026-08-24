package com.chen.football.prediction.controller;

import com.chen.football.common.context.UserContext;
import com.chen.football.common.dto.ApiResponse;
import com.chen.football.common.dto.MatchPredictionRequest;
import com.chen.football.common.dto.MatchPredictionResponse;
import com.chen.football.prediction.entity.PredictionEntity;
import com.chen.football.prediction.service.PersistencePredictionService;
import com.chen.football.prediction.service.MatchPredictionPrecomputeService;
import com.chen.football.common.util.AdminGuard;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/predictions")
public class PredictionController {

    private final PersistencePredictionService predictionService;
    private final MatchPredictionPrecomputeService matchPredictionPrecomputeService;

    public PredictionController(PersistencePredictionService predictionService,
                                MatchPredictionPrecomputeService matchPredictionPrecomputeService) {
        this.predictionService = predictionService;
        this.matchPredictionPrecomputeService = matchPredictionPrecomputeService;
    }

    @PostMapping("/match-result")
    public ApiResponse<MatchPredictionResponse> predict(@RequestBody MatchPredictionRequest req) {
        // 统一预测已由比赛级快照提供；此接口只负责保存当前用户的预测记录，
        // 禁止匿名请求伪造 userId 或反复消耗模型推理资源。
        AdminGuard.requireLogin();
        return ApiResponse.ok(predictionService.predictAndSave(req));
    }

    @GetMapping("/history")
    public ApiResponse<List<PredictionEntity>> history(@RequestParam(name = "limit", defaultValue = "20") int limit) {
        Long userId = UserContext.getUserId();
        int safeLimit = Math.max(1, Math.min(limit, 200));
        if (userId != null) {
            return ApiResponse.ok(predictionService.latestByUser(userId, safeLimit));
        }
        // Prediction history is a user-owned record. Never expose another
        // user's rows as a public fallback when the browser has no token.
        return ApiResponse.ok(List.of());
    }

    @GetMapping("/history/page")
    public ApiResponse<?> historyPage(@RequestParam(name = "cursor", required = false) Long cursor,
                                      @RequestParam(name = "size", defaultValue = "20") int size) {
        return ApiResponse.ok(predictionService.historyPage(UserContext.getUserId(), cursor, size));
    }

    /** 今日预测（按创建时间倒序） */
    @GetMapping("/today")
    public ApiResponse<List<PredictionEntity>> today(@RequestParam(name = "limit", defaultValue = "50") int limit) {
        Long userId = UserContext.getUserId();
        return ApiResponse.ok(predictionService.todayByUser(userId, Math.max(1, Math.min(limit, 200))));
    }

    /** 工作台复盘：只返回当前用户自己的预测，避免把用户预测实体公开成“热门”。 */
    @GetMapping("/hot")
    public ApiResponse<List<PredictionEntity>> hot(@RequestParam(name = "limit", defaultValue = "10") int limit) {
        Long userId = UserContext.getUserId();
        if (userId == null) return ApiResponse.ok(List.of());
        return ApiResponse.ok(predictionService.latestByUser(userId, Math.max(1, Math.min(limit, 50))));
    }

    /** 比赛级统一预测快照；不包含任何用户预测记录。 */
    @GetMapping("/ready")
    public ApiResponse<?> ready(@RequestParam(name = "limit", defaultValue = "100") int limit) {
        var result = matchPredictionPrecomputeService.readPublicSnapshotResult(limit);
        return ApiResponse.ok(Map.of("items", result.items(), "quality", result.quality()));
    }

    /** 指定比赛的历史预测 */
    @GetMapping("/match/{fixtureId}")
    public ApiResponse<List<PredictionEntity>> byMatch(@PathVariable("fixtureId") Long fixtureId,
                                                       @RequestParam(name = "limit", defaultValue = "5") int limit) {
        if (fixtureId == null || fixtureId <= 0) {
            return ApiResponse.ok(List.of());
        }
        return ApiResponse.ok(predictionService.byFixtureForUser(UserContext.getUserId(), fixtureId, Math.max(1, Math.min(limit, 20))));
    }

    /** 比赛级统一预测快照；首次访问没有结果时会异步排队生成。 */
    @GetMapping("/match/{fixtureId}/current")
    public ApiResponse<?> current(@PathVariable("fixtureId") Long fixtureId) {
        return ApiResponse.ok(matchPredictionPrecomputeService.getOrSchedule(fixtureId));
    }

    @GetMapping("/statistics")
    public ApiResponse<?> statistics() {
        Long userId = UserContext.getUserId();
        var stats = predictionService.getStatistics(userId);
        return ApiResponse.ok(stats);
    }

    @GetMapping("/performance")
    public ApiResponse<?> performance(@RequestParam(name = "days", defaultValue = "7") int days) {
        return ApiResponse.ok(predictionService.getPublicPerformance(days));
    }
}
