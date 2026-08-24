package com.chen.football.agent.tool;

import com.chen.football.common.dto.MatchPredictionRequest;
import com.chen.football.common.dto.MatchPredictionResponse;
import com.chen.football.prediction.service.PersistencePredictionService;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class PredictionTool implements AgentTool {

    private final PersistencePredictionService predictionService;

    public PredictionTool(PersistencePredictionService predictionService) {
        this.predictionService = predictionService;
    }

    @Override
    public String name() {
        return "prediction";
    }

    @Override
    public Map<String, Object> execute(Map<String, Object> context) {
        Long fixtureId = toLong(context.get("fixtureId"));
        Map<String, Object> missing = new LinkedHashMap<>();
        if (fixtureId == null || !Boolean.TRUE.equals(context.get("serverContextVerified"))) {
            missing.put("fixtureId", null);
            missing.put("status", "MISSING_INPUT");
            missing.put("predictionAvailable", false);
            missing.put("fallbackReason", "缺少服务端确认的比赛上下文，拒绝使用客户端传入的概率");
            return missing;
        }

        MatchPredictionRequest request = new MatchPredictionRequest(
                fixtureId,
                string(context.get("homeTeamId")),
                string(context.get("awayTeamId")),
                string(context.get("homeTeamName")),
                string(context.get("awayTeamName")),
                string(context.get("leagueName")),
                null,
                toLong(context.get("userId")),
                null,
                null,
                null,
                null,
                null,
                string(context.get("matchTime")),
                string(context.get("homeTeamLogo")),
                string(context.get("awayTeamLogo"))
        );
        // 预测必须由服务端按 fixtureId 读取/计算。客户端可传入的概率、结论和解释
        // 一律不作为事实，避免篡改 URL 或上下文后污染 Agent 回答。
        MatchPredictionResponse response = predictionService.predictOnly(request);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("fixtureId", response.fixtureId());
        data.put("resultLabel", response.resultLabel());
        data.put("homeWinProb", response.homeWinProb());
        data.put("drawProb", response.drawProb());
        data.put("awayWinProb", response.awayWinProb());
        data.put("modelVersion", response.modelVersion());
        data.put("explanation", response.explanation());
        data.put("topFeatures", response.topFeatures());
        data.put("featureComplete", response.featureComplete());
        data.put("featureStatus", response.featureStatus());
        data.put("fallbackReason", response.fallbackReason());
        data.put("featureMeta", response.featureMeta());
        data.put("predictionAvailable", response.predictionAvailable());
        data.put("status", response.predictionAvailable() ? "AVAILABLE" : "PARTIAL");
        data.put("message", response.predictionAvailable() ? "已读取服务端预测结果" : "预测特征不足，暂不输出结论");
        return data;
    }

    private Long toLong(Object value) {
        try {
            return value == null ? null : Long.valueOf(String.valueOf(value));
        } catch (Exception e) {
            return null;
        }
    }

    private String string(Object value) {
        return value == null ? null : String.valueOf(value);
    }

}
