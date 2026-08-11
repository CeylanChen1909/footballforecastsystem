from flask import Flask, request, jsonify
from flask_cors import CORS
import joblib
import numpy as np
import os
import sys

app = Flask(__name__)
CORS(app, resources={r"/*": {"origins": "*"}})

MODEL_DIR = os.path.join(os.path.dirname(__file__), "models")
MODEL_PATH = os.path.join(MODEL_DIR, "xgboost_model.json")
SCALER_PATH = os.path.join(MODEL_DIR, "feature_scaler.joblib")
FEATURE_NAMES_PATH = os.path.join(MODEL_DIR, "feature_names.txt")

model = None
scaler = None
feature_names = None
model_ready = False


def load_model():
    global model, scaler, feature_names, model_ready
    if os.path.exists(MODEL_PATH):
        try:
            model = joblib.load(MODEL_PATH)
            print(f"[ML] XGBoost model loaded from {MODEL_PATH}")
            model_ready = True
        except Exception as e:
            print(f"[ML] Failed to load model: {e}")
            model_ready = False
    else:
        print(f"[ML] Model file not found at {MODEL_PATH}, will use baseline mode")
        model_ready = False

    if os.path.exists(SCALER_PATH):
        try:
            scaler = joblib.load(SCALER_PATH)
        except Exception as e:
            print(f"[ML] Failed to load scaler: {e}")

    if os.path.exists(FEATURE_NAMES_PATH):
        with open(FEATURE_NAMES_PATH, "r") as f:
            feature_names = [line.strip() for line in f.readlines()]


load_model()


FEATURE_KEYS = [
    "home_elo", "away_elo", "elo_diff", "home_win_rate", "away_win_rate",
    "home_avg_goals", "away_avg_goals", "home_avg_loss", "away_avg_loss",
    "home_avg_cards", "away_avg_cards", "home_days_rest", "away_days_rest",
    "h2h_home_wins", "h2h_draws", "h2h_away_wins", "home_win_rate_diff",
    "elo_sum", "home_goal_diff", "avg_total_goals"
]


def build_features(data):
    """
    Build feature vector from request data.
    Feature list (in order):
      0  home_elo
      1  away_elo
      2  elo_diff
      3  home_win_rate
      4  away_win_rate
      5  home_avg_goals
      6  away_avg_goals
      7  home_avg_loss
      8  away_avg_loss
      9  home_avg_cards
      10 away_avg_cards
      11 home_days_rest
      12 away_days_rest
      13 h2h_home_wins
      14 h2h_draws
      15 h2h_away_wins
      16 home_win_rate_diff
      17 elo_sum
      18 home_goal_diff
      19 avg_total_goals
    """
    h_elo = float(data.get("home_elo", 1500.0))
    a_elo = float(data.get("away_elo", 1500.0))
    h_wr = float(data.get("home_win_rate", 0.45))
    a_wr = float(data.get("away_win_rate", 0.45))
    h_ag = float(data.get("home_avg_goals", 1.5))
    a_ag = float(data.get("away_avg_goals", 1.5))
    h_al = float(data.get("home_avg_loss", 1.2))
    a_al = float(data.get("away_avg_loss", 1.2))
    h_ac = float(data.get("home_avg_cards", 1.5))
    a_ac = float(data.get("away_avg_cards", 1.5))
    h_dr = int(data.get("home_days_rest", 7))
    a_dr = int(data.get("away_days_rest", 7))
    h2h_hw = int(data.get("h2h_home_wins", 0))
    h2h_d = int(data.get("h2h_draws", 0))
    h2h_aw = int(data.get("h2h_away_wins", 0))

    elo_diff = h_elo - a_elo
    wr_diff = h_wr - a_wr
    elo_sum = h_elo + a_elo
    goal_diff = h_ag - a_ag
    total_goals = h_ag + a_ag

    features = [
        h_elo, a_elo,
        elo_diff,
        h_wr, a_wr,
        h_ag, a_ag,
        h_al, a_al,
        h_ac, a_ac,
        h_dr, a_dr,
        h2h_hw, h2h_d, h2h_aw,
        wr_diff,
        elo_sum,
        goal_diff,
        total_goals
    ]
    return np.array(features, dtype=np.float64).reshape(1, -1)


def build_top_features(data):
    h_elo = float(data.get("home_elo", 1500.0))
    a_elo = float(data.get("away_elo", 1500.0))
    h_wr = float(data.get("home_win_rate", 0.45))
    a_wr = float(data.get("away_win_rate", 0.45))
    h_ag = float(data.get("home_avg_goals", 1.5))
    a_ag = float(data.get("away_avg_goals", 1.5))
    h_al = float(data.get("home_avg_loss", 1.2))
    a_al = float(data.get("away_avg_loss", 1.2))
    h_dr = int(data.get("home_days_rest", 7))
    a_dr = int(data.get("away_days_rest", 7))
    h2h_hw = int(data.get("h2h_home_wins", 0))
    h2h_d = int(data.get("h2h_draws", 0))
    h2h_aw = int(data.get("h2h_away_wins", 0))

    raw = [
        {"feature": "elo_diff", "label": "ELO差值", "importance": abs(h_elo - a_elo), "value": round(h_elo - a_elo, 2), "unit": "rating"},
        {"feature": "win_rate_diff", "label": "近期胜率差", "importance": abs(h_wr - a_wr) * 100, "value": round((h_wr - a_wr) * 100, 2), "unit": "%"},
        {"feature": "goal_diff", "label": "场均进球差", "importance": abs(h_ag - a_ag) * 10, "value": round(h_ag - a_ag, 2), "unit": "goals"},
        {"feature": "defensive_diff", "label": "场均失球差", "importance": abs(h_al - a_al) * 10, "value": round(h_al - a_al, 2), "unit": "goals"},
        {"feature": "h2h_balance", "label": "历史交锋平衡", "importance": abs(h2h_hw - h2h_aw) * 5 + h2h_d * 2, "value": h2h_hw - h2h_aw, "unit": "matches"},
        {"feature": "rest_days_diff", "label": "休息天数差", "importance": abs(h_dr - a_dr) * 2, "value": h_dr - a_dr, "unit": "days"},
        {"feature": "home_team_strength", "label": "主队综合强度", "importance": h_elo * 0.25 + h_wr * 60 + h_ag * 20, "value": round(h_elo * 0.25 + h_wr * 60 + h_ag * 20, 2), "unit": "score"},
        {"feature": "away_team_strength", "label": "客队综合强度", "importance": a_elo * 0.25 + a_wr * 60 + a_ag * 20, "value": round(a_elo * 0.25 + a_wr * 60 + a_ag * 20, 2), "unit": "score"},
    ]
    raw.sort(key=lambda x: x["importance"], reverse=True)
    return raw[:5]


def baseline_prediction(data):
    """Simple ELO-based fallback when model is not trained."""
    h_elo = float(data.get("home_elo", 1500.0))
    a_elo = float(data.get("away_elo", 1500.0))
    elo_diff = h_elo - a_elo

    # ELO expected score
    h_prob = 1 / (1 + 10 ** (-elo_diff / 400))
    a_prob = 1 / (1 + 10 ** (elo_diff / 400))
    d_prob = max(0.05, 1.0 - h_prob - a_prob)

    total = h_prob + a_prob + d_prob
    h_prob /= total
    a_prob /= total
    d_prob /= total

    if h_prob >= d_prob and h_prob >= a_prob:
        label = "HOME_WIN"
        explanation = (
            f"基于ELO评分系统({int(h_elo)} vs {int(a_elo)})分析，"
            f"主队理论胜率{h_prob*100:.1f}%，主场优势明显"
        )
    elif a_prob >= h_prob:
        label = "AWAY_WIN"
        explanation = (
            f"客队ELO评分({int(a_elo)})更高，理论胜率{a_prob*100:.1f}%，"
            f"交锋往绩对客队有利"
        )
    else:
        label = "DRAW"
        explanation = "两队实力接近，平局概率最大，预计双方握手言和"

    return {
        "homeWinProb": round(h_prob, 4),
        "drawProb": round(d_prob, 4),
        "awayWinProb": round(a_prob, 4),
        "resultLabel": label,
        "modelVersion": "baseline-elo-v1",
        "explanation": explanation,
        "topFeatures": build_top_features(data)
    }


@app.route("/health", methods=["GET"])
def health():
    return jsonify({"status": "ok", "model_ready": model_ready})


@app.route("/predict", methods=["POST"])
def predict():
    """
    POST /predict
    Body: {
        home_elo, away_elo,
        home_win_rate, away_win_rate,
        home_avg_goals, away_avg_goals,
        home_avg_loss, away_avg_loss,
        home_avg_cards, away_avg_cards,
        home_days_rest, away_days_rest,
        h2h_home_wins, h2h_draws, h2h_away_wins
    }
    Returns: { homeWinProb, drawProb, awayWinProb, resultLabel, modelVersion, explanation }
    """
    try:
        data = request.get_json()
    except Exception:
        return jsonify({"error": "Invalid JSON"}), 400

    if not model_ready or model is None:
        result = baseline_prediction(data)
        return jsonify(result)

    try:
        features = build_features(data)
        if scaler is not None:
            features = scaler.transform(features)

        # XGBoost predict_proba returns [prob_class0, prob_class1, prob_class2]
        # class 0 = HOME_WIN, class 1 = DRAW, class 2 = AWAY_WIN
        probs = model.predict_proba(features)[0]
        home_win_prob = float(probs[0])
        draw_prob = float(probs[1])
        away_win_prob = float(probs[2])

        if home_win_prob >= draw_prob and home_win_prob >= away_win_prob:
            result_label = "HOME_WIN"
        elif away_win_prob >= home_win_prob:
            result_label = "AWAY_WIN"
        else:
            result_label = "DRAW"

        explanation = (
            f"XGBoost模型预测：主队胜率{home_win_prob*100:.1f}%，"
            f"平局概率{draw_prob*100:.1f}%，客队胜率{away_win_prob*100:.1f}%。"
            f"综合考虑双方近期表现、历史交锋及主客场因素。"
        )

        return jsonify({
            "homeWinProb": round(home_win_prob, 4),
            "drawProb": round(draw_prob, 4),
            "awayWinProb": round(away_win_prob, 4),
            "resultLabel": result_label,
            "modelVersion": "xgboost-v2",
            "explanation": explanation,
            "topFeatures": build_top_features(data)
        })

    except Exception as e:
        print(f"[ML] Prediction error: {e}", file=sys.stderr)
        return jsonify({"error": str(e)}), 500


@app.route("/reload", methods=["POST"])
def reload_model():
    """Reload the trained model."""
    global model, scaler, model_ready
    load_model()
    return jsonify({"model_ready": model_ready})


if __name__ == "__main__":
    port = int(os.environ.get("PORT", 5001))
    app.run(host="0.0.0.0", port=port, debug=False)
