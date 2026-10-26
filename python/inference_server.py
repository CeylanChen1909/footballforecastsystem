# -*- coding: utf-8 -*-
"""
XGBoost 推理脚本 - 提供 HTTP API（Flask），供 Java 服务调用
POST /predict
{
    "home_team_id": 33,
    "away_team_id": 40,
    "home_elo": 1620,
    "away_elo": 1580,
    "home_win_rate": 0.55,
    "away_win_rate": 0.45,
    "home_avg_goals": 1.8,
    "away_avg_goals": 1.4,
    "home_avg_loss": 1.2,
    "away_avg_loss": 1.5,
    "home_avg_cards": 1.8,
    "away_avg_cards": 2.1,
    "home_days_rest": 5,
    "away_days_rest": 3,
    "h2h_home_wins": 3,
    "h2h_draws": 1,
    "h2h_away_wins": 1
}
返回:
{
    "fixtureId": null,
    "resultLabel": "HOME_WIN",
    "homeWinProb": 0.52,
    "drawProb": 0.23,
    "awayWinProb": 0.25,
    "modelVersion": "xgboost-v1",
    "explanation": "根据主队ELO更高且近期胜率更优，预测主队获胜概率最大"
}
"""
import os
import sys
import json
import joblib
try:
    import xgboost as xgb
except ImportError:  # optional for lightweight unit tests / environments without the model runtime
    xgb = None
from flask import Flask, request, jsonify, has_request_context

BASE_DIR = os.path.dirname(os.path.abspath(__file__))
MODEL_DIR = os.path.join(BASE_DIR, "models")
MODEL_PATH = os.path.join(MODEL_DIR, "xgboost_model.json")
ENCODER_PATH = os.path.join(MODEL_DIR, "team_encoder.pkl")
MODEL_VERSION = "xgboost-v1"

FEATURE_COLS = [
    "home_team_id", "away_team_id",
    "home_elo", "away_elo",
    "home_win_rate", "away_win_rate",
    "home_avg_goals", "away_avg_goals",
    "home_avg_loss", "away_avg_loss",
    "home_avg_cards", "away_avg_cards",
    "home_days_rest", "away_days_rest",
    "h2h_home_wins", "h2h_draws", "h2h_away_wins",
]

LABEL_NAMES = ["HOME_WIN", "DRAW", "AWAY_WIN"]

app = Flask(__name__)


def normalize_lang(value):
    if value is None:
        return None
    raw = str(value).strip().lower().replace("_", "-")
    if raw.startswith("zh"):
        return "zh"
    if raw.startswith("en"):
        return "en"
    return None


def _accept_language_lang(header):
    if not header:
        return None
    weighted = []
    for index, part in enumerate(str(header).split(",")):
        item = part.strip()
        if not item:
            continue
        tag = item
        quality = 1.0
        if ";" in item:
            tag, _, parameter = item.partition(";")
            tag = tag.strip()
            parameter = parameter.strip()
            if parameter.startswith("q="):
                try:
                    quality = float(parameter[2:])
                except ValueError:
                    quality = 1.0
        if quality <= 0:
            continue
        lang = normalize_lang(tag)
        if lang:
            weighted.append((quality, -index, lang))
    if not weighted:
        return None
    return sorted(weighted, reverse=True)[0][2]


def resolve_lang(data=None):
    """Resolve en|zh from ?lang, X-Lang, Accept-Language, body lang (default en)."""
    if has_request_context():
        explicit = normalize_lang(request.args.get("lang"))
        if explicit:
            return explicit
        explicit = normalize_lang(request.headers.get("X-Lang"))
        if explicit:
            return explicit
        accepted = _accept_language_lang(request.headers.get("Accept-Language"))
        if accepted:
            return accepted
    if isinstance(data, dict):
        explicit = normalize_lang(data.get("lang"))
        if explicit:
            return explicit
    return "en"


def tr(zh, en, lang):
    return zh if lang == "zh" else en


# 全局加载
model = None
team_encoder = None


def load_model():
    global model, team_encoder
    if xgb is not None and os.path.exists(MODEL_PATH):
        model = xgb.XGBClassifier()
        model.load_model(MODEL_PATH)
        print(f"[推理] 模型已加载: {MODEL_PATH}")
    else:
        print(f"[推理] 警告: 模型文件不存在 ({MODEL_PATH})，将返回占位预测")
        model = None

    if os.path.exists(ENCODER_PATH):
        team_encoder = joblib.load(ENCODER_PATH)
        print(f"[推理] 球队编码器已加载: {ENCODER_PATH}")
    else:
        team_encoder = None
        print(f"[推理] 警告: 编码器文件不存在")


def make_explanation(home_win_prob, draw_prob, away_win_prob, home_elo, away_elo,
                     home_win_rate, away_win_rate, lang="en"):
    """Generate a language-aware explanation."""
    winner = LABEL_NAMES[max(range(3), key=lambda i: [home_win_prob, draw_prob, away_win_prob][i])]
    if winner == "HOME_WIN":
        parts = []
        if home_elo > away_elo:
            parts.append(f"Home ELO ({home_elo:.0f}) is higher than the away side ({away_elo:.0f})" if lang == "en"
                         else f"主队ELO({home_elo:.0f})高于客队({away_elo:.0f})")
        if home_win_rate > away_win_rate:
            parts.append(f"Home recent win rate ({home_win_rate:.0%}) is better than the away side ({away_win_rate:.0%})" if lang == "en"
                         else f"主队近期胜率({home_win_rate:.0%})优于客队({away_win_rate:.0%})")
        reason = ("; ".join(parts) if parts else "Overall data favours the home team") if lang == "en" else ("，".join(parts) if parts else "综合数据支持主队")
        return (f"The home team has the highest win probability ({home_win_prob:.1%}). {reason}." if lang == "en"
                else f"主队获胜概率最大({home_win_prob:.1%})。{reason}。")
    elif winner == "AWAY_WIN":
        parts = []
        if away_elo > home_elo:
            parts.append(f"Away ELO ({away_elo:.0f}) is higher than the home side ({home_elo:.0f})" if lang == "en"
                         else f"客队ELO({away_elo:.0f})高于主队({home_elo:.0f})")
        if away_win_rate > home_win_rate:
            parts.append(f"Away recent win rate ({away_win_rate:.0%}) is better than the home side ({home_win_rate:.0%})" if lang == "en"
                         else f"客队近期胜率({away_win_rate:.0%})优于主队({home_win_rate:.0%})")
        reason = ("; ".join(parts) if parts else "Overall data favours the away team") if lang == "en" else ("，".join(parts) if parts else "综合数据支持客队")
        return (f"The away team has the highest win probability ({away_win_prob:.1%}). {reason}." if lang == "en"
                else f"客队获胜概率最大({away_win_prob:.1%})。{reason}。")
    else:
        return (f"The sides are evenly matched; a draw is most likely ({draw_prob:.1%})." if lang == "en"
                else f"势均力敌，平局概率最高({draw_prob:.1%})，主客队实力接近。")


def predict(data: dict, lang: str = "en") -> dict:
    """执行预测，返回标准格式字典"""
    # 编码球队 ID
    home_id = data.get("home_team_id")
    away_id = data.get("away_team_id")

    if team_encoder is not None and model is not None:
        try:
            home_encoded = team_encoder.transform([home_id])[0]
            away_encoded = team_encoder.transform([away_id])[0]
        except Exception:
            # 未知球队，使用默认值
            home_encoded = home_id if isinstance(home_id, int) else 0
            away_encoded = away_id if isinstance(away_id, int) else 0
    else:
        home_encoded = home_id if isinstance(home_id, int) else 0
        away_encoded = away_id if isinstance(away_id, int) else 0

    # 构建特征向量
    feature_values = [
        home_encoded,
        away_encoded,
        data.get("home_elo", 1500),
        data.get("away_elo", 1500),
        data.get("home_win_rate", 0.5),
        data.get("away_win_rate", 0.5),
        data.get("home_avg_goals", 1.5),
        data.get("away_avg_goals", 1.5),
        data.get("home_avg_loss", 1.5),
        data.get("away_avg_loss", 1.5),
        data.get("home_avg_cards", 1.5),
        data.get("away_avg_cards", 1.5),
        data.get("home_days_rest", 7),
        data.get("away_days_rest", 7),
        data.get("h2h_home_wins", 0),
        data.get("h2h_draws", 0),
        data.get("h2h_away_wins", 0),
    ]

    if model is not None:
        probs = model.predict_proba([feature_values])[0]
        home_win_prob, draw_prob, away_win_prob = float(probs[0]), float(probs[1]), float(probs[2])
    else:
        # 占位预测（无模型时）
        home_win_prob = data.get("home_elo", 1500) / 3000
        away_win_prob = data.get("away_elo", 1500) / 3000
        total = home_win_prob + away_win_prob + 0.01
        home_win_prob /= total
        away_win_prob /= total
        draw_prob = 1.0 - home_win_prob - away_win_prob

    result_label = LABEL_NAMES[max(range(3), key=lambda i: [home_win_prob, draw_prob, away_win_prob][i])]
    explanation = make_explanation(
        home_win_prob, draw_prob, away_win_prob,
        data.get("home_elo", 1500), data.get("away_elo", 1500),
        data.get("home_win_rate", 0.5), data.get("away_win_rate", 0.5), lang
    )

    ordered = sorted([home_win_prob, draw_prob, away_win_prob], reverse=True)
    margin = max(0.0, ordered[0] - ordered[1])
    confidence_label = (tr("倾向明确", "Clear lean", lang) if margin >= 0.20 else
                        (tr("存在分歧", "Split signals", lang) if margin >= 0.08 else tr("接近均势", "Close to even", lang)))
    fallback_reason = None if model is not None else tr(
        "模型文件不存在，使用占位预测", "Model file not found; placeholder prediction used", lang)
    return {
        "fixtureId": data.get("fixture_id"),
        "resultLabel": result_label,
        "homeWinProb": round(home_win_prob, 4),
        "drawProb": round(draw_prob, 4),
        "awayWinProb": round(away_win_prob, 4),
        "modelVersion": MODEL_VERSION,
        "explanation": explanation,
        "confidenceLabel": confidence_label,
        "confidenceType": "decision-margin",
        "decisionMargin": round(margin, 4),
        "fallbackReason": fallback_reason,
        "qualityGate": {
            "eligible": model is not None,
            "message": (tr("模型已加载", "Model loaded", lang) if model is not None
                        else tr("模型文件不存在，使用占位预测", "Model file not found; placeholder prediction used", lang)),
        },
    }


@app.route("/health", methods=["GET"])
def health():
    return jsonify({"status": "ok", "model_loaded": model is not None})


@app.route("/predict", methods=["POST"])
def predict_api():
    try:
        data = request.get_json(force=True)
    except Exception:
        return jsonify({"error": tr("无效的 JSON", "Invalid JSON", resolve_lang())}), 400

    if not data:
        return jsonify({"error": tr("请求体为空", "Request body is empty", resolve_lang())}), 400

    lang = resolve_lang(data)
    try:
        result = predict(data, lang)
        return jsonify(result)
    except Exception as e:
        return jsonify({"error": str(e)}), 500


@app.route("/predict/batch", methods=["POST"])
def predict_batch_api():
    try:
        items = request.get_json(force=True)
    except Exception:
        return jsonify({"error": tr("无效的 JSON", "Invalid JSON", resolve_lang())}), 400

    if not isinstance(items, list):
        return jsonify({"error": tr("请求体需要是数组", "Request body must be an array", resolve_lang())}), 400

    lang = resolve_lang()
    results = []
    for item in items:
        try:
            results.append(predict(item, resolve_lang(item) if isinstance(item, dict) else lang))
        except Exception as e:
            results.append({"error": str(e)})

    return jsonify(results)


if __name__ == "__main__":
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 5001
    load_model()
    print(f"[推理] Flask 服务启动，监听端口 {port}")
    app.run(host="0.0.0.0", port=port, debug=False)
