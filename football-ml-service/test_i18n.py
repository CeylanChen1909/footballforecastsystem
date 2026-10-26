# -*- coding: utf-8 -*-
import unittest

import app as ml


class MlI18nTest(unittest.TestCase):
    def test_default_language_is_english(self):
        with ml.app.test_request_context("/predict"):
            self.assertEqual("en", ml.resolve_lang())
        self.assertEqual("en", ml.resolve_lang({"home_elo": 1500}))

    def test_language_precedence_query_header_accept_language_body(self):
        with ml.app.test_request_context("/predict?lang=zh", headers={
            "X-Lang": "en", "Accept-Language": "en-US,en;q=0.9"}):
            self.assertEqual("zh", ml.resolve_lang({"lang": "en"}))
        with ml.app.test_request_context("/predict", headers={
            "X-Lang": "zh-CN", "Accept-Language": "en-US"}):
            self.assertEqual("zh", ml.resolve_lang())
        with ml.app.test_request_context("/predict", headers={"Accept-Language": "zh-CN,zh;q=0.9,en;q=0.8"}):
            self.assertEqual("zh", ml.resolve_lang())
        self.assertEqual("zh", ml.resolve_lang({"lang": "zh-TW"}))

    def test_confidence_label_and_quality_gate_are_localized(self):
        self.assertEqual("Clear lean", ml.prediction_metadata([0.6, 0.2, 0.2], lang="en")["confidenceLabel"])
        self.assertEqual("倾向明确", ml.prediction_metadata([0.6, 0.2, 0.2], lang="zh")["confidenceLabel"])
        self.assertEqual("Close to even", ml.prediction_metadata([0.36, 0.34, 0.30], lang="en")["confidenceLabel"])
        self.assertIn("baseline", ml.production_quality_gate("en")["message"].lower()
                      if not ml.production_quality_gate("en")["eligible"] else "gate")
        self.assertFalse(any("\u4e00" <= ch <= "\u9fff" for ch in ml.production_quality_gate("en")["message"]))

    def test_top_feature_labels_follow_language(self):
        labels_en = [item["label"] for item in ml.build_top_features({"home_elo": 1600, "away_elo": 1500}, "en")]
        labels_zh = [item["label"] for item in ml.build_top_features({"home_elo": 1600, "away_elo": 1500}, "zh")]
        self.assertTrue(all(not any("\u4e00" <= ch <= "\u9fff" for ch in label) for label in labels_en))
        self.assertTrue(any(any("\u4e00" <= ch <= "\u9fff" for ch in label) for label in labels_zh))

    def test_model_scope_warning_is_localized(self):
        original = ml.model_metrics
        try:
            ml.model_metrics = {"model_scope": {"warning": "单场概率经过时间滚动校准；仍建议结合数据完整度，低置信度结果不应强行解读"}}
            english = ml.localized_model_metrics("en")["model_scope"]["warning"]
            self.assertIn("calibrated", english.lower())
            self.assertFalse(any("\u4e00" <= ch <= "\u9fff" for ch in english))
            chinese = ml.localized_model_metrics("zh")["model_scope"]["warning"]
            self.assertIn("滚动校准", chinese)
        finally:
            ml.model_metrics = original

    def test_baseline_prediction_explanation_is_language_aware(self):
        data = {"home_elo": 1620, "away_elo": 1500, "home_win_rate": 0.6, "away_win_rate": 0.4}
        english = ml.baseline_prediction(data, "en")
        chinese = ml.baseline_prediction(data, "zh")
        self.assertFalse(any("\u4e00" <= ch <= "\u9fff" for ch in english["explanation"]))
        self.assertTrue(any("\u4e00" <= ch <= "\u9fff" for ch in chinese["explanation"]))
        self.assertEqual("decision-margin", english["confidenceType"])
        self.assertIn("confidenceLabel", english)

    def test_predict_endpoint_accepts_lang_query(self):
        original = ml.ML_REQUIRE_INTERNAL_AUTH
        try:
            ml.ML_REQUIRE_INTERNAL_AUTH = False
            client = ml.app.test_client()
            response = client.post("/predict?lang=en", json={
                "home_elo": 1620, "away_elo": 1500, "home_win_rate": 0.6, "away_win_rate": 0.4,
                "home_avg_goals": 1.8, "away_avg_goals": 1.2,
            })
            self.assertEqual(200, response.status_code)
            payload = response.get_json()
            self.assertIn("confidenceLabel", payload)
            self.assertFalse(any("\u4e00" <= ch <= "\u9fff" for ch in payload["explanation"]))
        finally:
            ml.ML_REQUIRE_INTERNAL_AUTH = original


if __name__ == "__main__":
    unittest.main()
