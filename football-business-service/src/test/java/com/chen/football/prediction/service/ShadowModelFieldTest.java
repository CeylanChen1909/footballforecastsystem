package com.chen.football.prediction.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P0-3 混合模型影子评估（2026-11-23 起，跑 2–4 周）：
 * 影子字段只活在 t_match_prediction.feature_meta_json 里，绝不外泄。
 *
 * <p>覆盖两件事：(1) {@code publicFeatureMeta} 去掉影子字段、保留基线元数据、
 * 不改动原 map；(2) 影子记录的字段契约——离线对照 SQL 依赖的 JSON 路径
 * （见 p0/reports/shadow.md §4），改字段名必须同步改 SQL。
 *
 * <p>未覆盖（需集成环境）：{@code collectShadowPrediction} 的真实 HTTP 调用
 * 与 {@code predictOnly} 的端到端落库——本机无 JDK/依赖，见 shadow.md §6。
 */
class ShadowModelFieldTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void publicFeatureMetaStripsShadowButKeepsBaselineMeta() {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("modelKind", "BASELINE");
        meta.put("qualityTier", "BASELINE_LIMITED");
        meta.put("missing", java.util.Set.of("lineups"));
        Map<String, Object> shadow = new LinkedHashMap<>();
        shadow.put("mode", "SHADOW_ONLY");
        shadow.put("status", "OK");
        shadow.put("modelVersion", "baseline-elo-poisson-v2");
        shadow.put("resultLabel", "HOME_WIN");
        meta.put(PersistencePredictionService.SHADOW_FIELD, shadow);

        Map<String, Object> publicMeta = PersistencePredictionService.publicFeatureMeta(meta);

        assertFalse(publicMeta.containsKey(PersistencePredictionService.SHADOW_FIELD));
        assertEquals("BASELINE", publicMeta.get("modelKind"));
        assertEquals("BASELINE_LIMITED", publicMeta.get("qualityTier"));
        // 原 map 不被改动：落库用的那份仍带影子记录。
        assertTrue(meta.containsKey(PersistencePredictionService.SHADOW_FIELD));
    }

    @Test
    void publicFeatureMetaHandlesNullAndShadowFreeMeta() {
        assertNull(PersistencePredictionService.publicFeatureMeta(null));
        Map<String, Object> meta = Map.of("modelKind", "BASELINE");
        assertSame(meta, PersistencePredictionService.publicFeatureMeta(meta));
    }

    @Test
    void shadowRecordCarriesGateReasonAndBaselineSeparation() throws Exception {
        // 与 collectShadowPrediction 写入 feature_meta_json 的结构逐字段一致
        //（status=OK、门控不通过时的真实形态：Python 兜底回基线）。
        Map<String, Object> shadow = sampleShadowRecord();
        assertEquals("SHADOW_ONLY", shadow.get("mode"));
        assertEquals("OK", shadow.get("status"));
        assertNotNull(shadow.get("evaluatedAt"));
        assertEquals("baseline-elo-poisson-v2", shadow.get("modelVersion"));
        assertEquals("global-generic", shadow.get("modelRoute"));

        @SuppressWarnings("unchecked")
        Map<String, Object> qualityGate = (Map<String, Object>) shadow.get("qualityGate");
        assertNotNull(qualityGate, "门控失败原因必须随行记录，否则影子数据无法回答“为什么没用混合模型”");
        assertEquals(Boolean.FALSE, qualityGate.get("eligible"));
        @SuppressWarnings("unchecked")
        Map<String, Object> checks = (Map<String, Object>) qualityGate.get("checks");
        assertEquals(Boolean.FALSE, checks.get("drawRecall"));
        assertEquals(Boolean.FALSE, checks.get("accuracyEdge"));

        @SuppressWarnings("unchecked")
        Map<String, Object> javaGate = (Map<String, Object>) shadow.get("javaGate");
        assertEquals(Boolean.FALSE, javaGate.get("pythonReady"));
        assertTrue(javaGate.get("optionalMissing").toString().contains("lineups"));

        // 影子概率与基线概率不在同一层：行级列（result_label/home_win_prob…）
        // 永远是基线，影子只在 shadowModel 子对象里。
        assertTrue(shadow.containsKey("homeWinProb"));
        assertFalse(shadow.containsKey("modelKind"));
    }

    /** 与生产实际一致的影子记录样本（Python 门控不通过 → 兜底基线）。 */
    private static Map<String, Object> sampleShadowRecord() throws Exception {
        String json = """
                {
                  "mode": "SHADOW_ONLY",
                  "evaluatedAt": "2026-11-23T14:00:00Z",
                  "status": "OK",
                  "javaGate": {
                    "pythonReady": false,
                    "optionalMissing": ["lineups", "injuries", "odds"],
                    "pythonEnabled": true
                  },
                  "modelVersion": "baseline-elo-poisson-v2",
                  "modelRoute": "global-generic",
                  "resultLabel": "HOME_WIN",
                  "homeWinProb": 0.4512,
                  "drawProb": 0.2611,
                  "awayWinProb": 0.2877,
                  "qualityGate": {
                    "eligible": false,
                    "checks": {
                      "promotionGateVersion": true,
                      "promotionAccepted": false,
                      "accuracyEdge": false,
                      "balancedAccuracy": false,
                      "drawRecall": false,
                      "stability": true
                    },
                    "message": "The current model does not clearly beat the baseline or lacks draw recognition; a transparent ELO fallback is used."
                  },
                  "decisionMargin": 0.1635,
                  "confidenceStatus": "CALIBRATED"
                }
                """;
        return MAPPER.readValue(json, new TypeReference<>() {});
    }
}
