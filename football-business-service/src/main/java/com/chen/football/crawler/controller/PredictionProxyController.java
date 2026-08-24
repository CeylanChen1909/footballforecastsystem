package com.chen.football.crawler.controller;

import com.chen.football.common.dto.ApiResponse;
import com.chen.football.crawler.service.DeepSeekPredictionService;
import com.chen.football.crawler.service.ExternalPredictionProxyService;
import com.chen.football.common.util.AdminGuard;
import com.chen.football.common.context.UserContext;
import com.chen.football.agent.service.AgentRateLimitService;
import com.chen.football.common.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/proxy")
@RequiredArgsConstructor
public class PredictionProxyController {

    @Value("${legacy.prediction-proxy.enabled:false}")
    private boolean legacyEnabled;

    private final ExternalPredictionProxyService proxyService;
    private final DeepSeekPredictionService deepSeekPredictionService;
    private final AgentRateLimitService rateLimitService;

    private void guardModelCall() {
        if (!legacyEnabled) {
            throw new ResponseStatusException(HttpStatus.GONE, "旧版预测接口已下线，请使用比赛级预测快照");
        }
        AdminGuard.requireLogin();
        if (!rateLimitService.allow(UserContext.getUserId())) throw new BusinessException("模型请求过于频繁，请稍后再试");
    }

    @GetMapping("/prediction")
    public ApiResponse<Map<String, Object>> predict(@RequestParam(name = "fixtureId", required = false) Long fixtureId,
                                                    @RequestParam(name = "homeTeam", required = false) String homeTeam,
                                                    @RequestParam(name = "awayTeam", required = false) String awayTeam,
                                                    @RequestParam(name = "leagueName", required = false) String leagueName) {
        guardModelCall();
        Map<String, Object> base = proxyService.predict(fixtureId, homeTeam, awayTeam, leagueName);
        String prompt = String.format("请基于以下比赛给出足球预测JSON，只输出JSON对象，不要多余文本。要求字段：homeWinProb, drawProb, awayWinProb, resultLabel, tactical, basis, risk。比赛：fixtureId=%s, homeTeam=%s, awayTeam=%s, leagueName=%s。", fixtureId, homeTeam, awayTeam, leagueName);
        Map<String, Object> ai = deepSeekPredictionService.analyzeJson(prompt);
        Map<String, Object> merged = new LinkedHashMap<>(base);
        merged.put("ai", ai);
        merged.put("source", ai.getOrDefault("source", "proxy-heuristic-v2"));
        Object content = ai.get("content");
        if (content != null && !String.valueOf(content).isBlank()) {
            merged.put("content", String.valueOf(content));
            merged.put("summary", String.valueOf(content));
        }
        return ApiResponse.ok(merged);
    }

    @GetMapping("/analysis")
    public ApiResponse<Map<String, Object>> analysis(@RequestParam(name = "fixtureId", required = false) Long fixtureId,
                                                     @RequestParam(name = "homeTeam", required = false) String homeTeam,
                                                     @RequestParam(name = "awayTeam", required = false) String awayTeam,
                                                     @RequestParam(name = "leagueName", required = false) String leagueName,
                                                     @RequestParam(name = "homeWinProb", required = false) Double homeWinProb,
                                                     @RequestParam(name = "drawProb", required = false) Double drawProb,
                                                     @RequestParam(name = "awayWinProb", required = false) Double awayWinProb,
                                                     @RequestParam(name = "resultLabel", required = false) String resultLabel,
                                                     @RequestParam(name = "explanation", required = false) String explanation) {
        guardModelCall();
        String prompt = String.format("请基于以下足球比赛预测结果，输出一段简洁、专业、可直接展示给用户的中文补充分析，重点说明概率含义、比赛走势和风险提示，避免空话，不要输出 Markdown 表格。比赛：%s vs %s；联赛：%s；fixtureId：%s；主胜概率：%s；平局概率：%s；客胜概率：%s；结果标签：%s；本地解释：%s。请用 3~5 句话完成输出。",
                homeTeam, awayTeam, leagueName, fixtureId, homeWinProb, drawProb, awayWinProb, resultLabel, explanation);
        Map<String, Object> ai = deepSeekPredictionService.analyzeJson(prompt);
        Map<String, Object> merged = new LinkedHashMap<>();
        merged.put("fixtureId", fixtureId);
        merged.put("source", ai.getOrDefault("source", "deepseek"));
        merged.put("content", ai.get("content"));
        merged.put("status", ai.get("status"));
        merged.put("latencyMs", ai.get("latencyMs"));
        return ApiResponse.ok(merged);
    }

    @GetMapping("/h2h")
    public ApiResponse<Map<String, Object>> h2h(@RequestParam(name = "homeTeam") String homeTeam,
                                                @RequestParam(name = "awayTeam") String awayTeam,
                                                @RequestParam(name = "limit", defaultValue = "10") int limit) {
        guardModelCall();
        Map<String, Object> base = proxyService.headToHead(homeTeam, awayTeam, limit);
        String prompt = String.format("请只输出一句简洁结论，格式为：XXX主队胜 / XXX平局 / XXX客队胜。球队：%s vs %s。", homeTeam, awayTeam);
        Map<String, Object> ai = deepSeekPredictionService.analyzeJson(prompt);
        Map<String, Object> merged = new LinkedHashMap<>(base);
        merged.put("ai", ai);
        return ApiResponse.ok(merged);
    }
}
