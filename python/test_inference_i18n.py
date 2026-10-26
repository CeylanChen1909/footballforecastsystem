# -*- coding: utf-8 -*-
import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import inference_server as inference


class InferenceI18nTest(unittest.TestCase):
    def test_default_language_is_english(self):
        with inference.app.test_request_context("/predict"):
            self.assertEqual("en", inference.resolve_lang())
        self.assertEqual("en", inference.resolve_lang({"home_elo": 1500}))

    def test_language_precedence(self):
        with inference.app.test_request_context("/predict?lang=zh", headers={
            "X-Lang": "en", "Accept-Language": "en-US"}):
            self.assertEqual("zh", inference.resolve_lang())
        with inference.app.test_request_context("/predict", headers={"X-Lang": "zh-CN"}):
            self.assertEqual("zh", inference.resolve_lang())
        with inference.app.test_request_context("/predict", headers={"Accept-Language": "zh-CN,zh;q=0.9"}):
            self.assertEqual("zh", inference.resolve_lang())

    def test_explanation_is_language_aware(self):
        english = inference.make_explanation(0.55, 0.25, 0.20, 1650, 1500, 0.6, 0.4, "en")
        chinese = inference.make_explanation(0.55, 0.25, 0.20, 1650, 1500, 0.6, 0.4, "zh")
        self.assertFalse(any("\u4e00" <= ch <= "\u9fff" for ch in english))
        self.assertTrue(any("\u4e00" <= ch <= "\u9fff" for ch in chinese))

    def test_predict_result_explanation_uses_lang(self):
        data = {"home_team_id": 1, "away_team_id": 2, "home_elo": 1620, "away_elo": 1500,
                "home_win_rate": 0.6, "away_win_rate": 0.4}
        english = inference.predict(data, "en")
        chinese = inference.predict(data, "zh")
        self.assertFalse(any("\u4e00" <= ch <= "\u9fff" for ch in english["explanation"]))
        self.assertTrue(any("\u4e00" <= ch <= "\u9fff" for ch in chinese["explanation"]))

    def test_predict_endpoint_accepts_x_lang_header(self):
        client = inference.app.test_client()
        response = client.post("/predict", headers={"X-Lang": "zh"},
                               json={"home_team_id": 1, "away_team_id": 2, "home_elo": 1600, "away_elo": 1500})
        self.assertEqual(200, response.status_code)
        payload = response.get_json()
        self.assertTrue(any("\u4e00" <= ch <= "\u9fff" for ch in payload["explanation"]))


if __name__ == "__main__":
    unittest.main()
