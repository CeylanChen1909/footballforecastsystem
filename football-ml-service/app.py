from flask import Flask, request, jsonify
from flask_cors import CORS
import joblib
import numpy as np
import os
import sys
import json
import math
import shutil

app = Flask(__name__)
# The inference service is private and receives compact JSON feature payloads.
# Refuse oversized bodies before Flask allocates/parses them; this protects the
# model worker from accidental or deliberate memory pressure.
app.config["MAX_CONTENT_LENGTH"] = int(os.environ.get("ML_MAX_REQUEST_BYTES", "65536"))
ML_ALLOWED_ORIGINS = [origin.strip() for origin in os.environ.get(
    "ML_ALLOWED_ORIGINS", "http://localhost:5173,http://127.0.0.1:5173"
).split(",") if origin.strip()]
CORS(app, resources={r"/*": {"origins": ML_ALLOWED_ORIGINS}})


@app.errorhandler(413)
def request_too_large(_error):
    return jsonify({"error": "请求体过大"}), 413

MODEL_DIR = os.path.join(os.path.dirname(__file__), "models")
MODEL_PATH = os.path.join(MODEL_DIR, "xgboost_model.json")
HYBRID_MODEL_PATH = os.path.join(MODEL_DIR, "hybrid_model.joblib")
SCALER_PATH = os.path.join(MODEL_DIR, "feature_scaler.joblib")
FEATURE_NAMES_PATH = os.path.join(MODEL_DIR, "feature_names.txt")
TRAIN_RESULTS_PATH = os.path.join(MODEL_DIR, "train_results.json")
CANDIDATE_TRAIN_RESULTS_PATH = os.path.join(MODEL_DIR, "train_results.candidate.json")
PREVIOUS_HYBRID_MODEL_PATH = os.path.join(MODEL_DIR, "hybrid_model.previous.joblib")
PREVIOUS_TRAIN_RESULTS_PATH = os.path.join(MODEL_DIR, "train_results.previous.json")
SPECIALIST_CODES = ("PL", "BL1", "PD", "FL1", "SA")
ML_ADMIN_TOKEN = os.environ.get("ML_ADMIN_TOKEN", "").strip()
ML_INTERNAL_TOKEN = os.environ.get("ML_INTERNAL_TOKEN", "").strip()
# The inference worker is an internal service.  Developers can explicitly
# opt out locally, but a missing environment variable must never expose /predict.
ML_REQUIRE_INTERNAL_AUTH = os.environ.get("ML_REQUIRE_INTERNAL_AUTH", "true").strip().lower() == "true"

model = None
hybrid_model = None
scaler = None
feature_names = None
model_ready = False
model_metrics = {}
specialist_models = {}
specialist_metrics = {}


def require_internal_auth():
    """Protect model inference/admin endpoints when the service is reachable on a network."""
    if not ML_REQUIRE_INTERNAL_AUTH:
        return None
    if not ML_INTERNAL_TOKEN:
        return jsonify({"ok": False, "message": "model internal authentication is not configured"}), 503
    provided = request.headers.get("X-ML-Internal-Token", "")
    if provided != ML_INTERNAL_TOKEN:
        return jsonify({"ok": False, "message": "unauthorized"}), 401
    return None


def load_model():
    global model, hybrid_model, scaler, feature_names, model_ready, model_metrics, specialist_models, specialist_metrics
    if os.path.exists(HYBRID_MODEL_PATH):
        try:
            hybrid_model = joblib.load(HYBRID_MODEL_PATH)
            print(f"[ML] Hybrid model loaded from {HYBRID_MODEL_PATH}")
        except Exception as e:
            print(f"[ML] Failed to load hybrid model: {e}")
            hybrid_model = None
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

    model_metrics = {}
    if os.path.exists(TRAIN_RESULTS_PATH):
        try:
            with open(TRAIN_RESULTS_PATH, "r", encoding="utf-8") as f:
                report = json.load(f)
            for key in ("production_gate_version", "trained_at", "dataset_size", "train_size", "validation_size", "test_size",
                        "accuracy", "baseline_accuracy", "balanced_accuracy", "precision", "recall", "f1",
                        "log_loss", "baseline_log_loss", "brier_score", "baseline_brier_score", "validation_log_loss",
                        "blend_weight_xgboost", "blend_weight_elo", "blend_weight_poisson", "blend_weight_model",
                        "blend_weight_catboost", "catboost_enabled",
                        "calibration_temperature", "abstain_threshold", "expected_calibration_error",
                        "class_bias",
                        "baseline_expected_calibration_error", "feature_missing_rate", "feature_zero_rate",
                        "enrichment_coverage",
                        "validation_draw_loss_weight", "selection_objective", "strategy",
                        "validation_strategy_metrics", "test_strategy_metrics", "walk_forward", "stability",
                        "split", "class_recall", "model_scope"):
                if key in report:
                    model_metrics[key] = report[key]
            if "promotion" in report:
                model_metrics["promotion"] = report["promotion"]
        except Exception as e:
            print(f"[ML] Failed to load training report: {e}")

    specialist_models = {}
    specialist_metrics = {}
    for code in SPECIALIST_CODES:
        code_key = code.lower()
        model_path = os.path.join(MODEL_DIR, f"league_{code_key}.joblib")
        report_path = os.path.join(MODEL_DIR, f"league_{code_key}.json")
        if not (os.path.exists(model_path) and os.path.exists(report_path)):
            continue
        try:
            with open(report_path, "r", encoding="utf-8") as report_file:
                report = json.load(report_file)
            specialist_metrics[code] = report
            if (report.get("promotion") or {}).get("accepted") is True:
                specialist_models[code] = joblib.load(model_path)
                print(f"[ML] League specialist loaded: {code}")
            else:
                print(f"[ML] League specialist rejected by gate: {code}")
        except Exception as exc:
            print(f"[ML] Failed to load league specialist {code}: {exc}")


load_model()


LEGACY_FEATURE_KEYS = [
    "home_elo", "away_elo", "elo_diff", "home_win_rate", "away_win_rate",
    "home_avg_goals", "away_avg_goals", "home_avg_loss", "away_avg_loss",
    "home_avg_cards", "away_avg_cards", "home_days_rest", "away_days_rest",
    "h2h_home_wins", "h2h_draws", "h2h_away_wins", "home_win_rate_diff",
    "elo_sum", "home_goal_diff", "avg_total_goals"
]
ADVANCED_FEATURE_KEYS = [
    "home_rank", "away_rank", "rank_diff",
    "home_points_per_match", "away_points_per_match", "points_per_match_diff",
    "home_goal_diff_per_match", "away_goal_diff_per_match", "goal_diff_per_match_diff",
    "home_form_5", "away_form_5", "home_form_10", "away_form_10",
    "home_home_form_5", "away_away_form_5",
    "home_matches_14d", "away_matches_14d", "matches_14d_diff",
    "home_xg_5", "away_xg_5", "home_xga_5", "away_xga_5",
    "home_shots_5", "away_shots_5", "home_shots_on_target_5", "away_shots_on_target_5",
    "home_lineup_stability", "away_lineup_stability",
    "home_injury_impact", "away_injury_impact",
    "market_home_prob", "market_draw_prob", "market_away_prob"
]
# Backward-compatible aliases used by health/debug clients.
FEATURE_KEYS = LEGACY_FEATURE_KEYS + ADVANCED_FEATURE_KEYS
OPTIONAL_FEATURE_KEYS = []


def validate_prediction_payload(data):
    """Validate the small numeric contract shared by Java and this service.

    Missing fields remain backward-compatible (the feature builder supplies
    documented defaults), but supplied values must be finite numbers.  Without
    this check NaN/Infinity can flow into sklearn and yield opaque 500 errors or
    non-deterministic probabilities.
    """
    if not isinstance(data, dict):
        return "请求体必须是 JSON 对象"
    for key, value in data.items():
        if not isinstance(value, (int, float)) or isinstance(value, bool):
            # Metadata fields are tolerated; feature-looking fields are not.
            if key in FEATURE_KEYS or key in ADVANCED_FEATURE_KEYS:
                return f"特征 {key} 必须是数字"
            continue
        if not math.isfinite(float(value)):
            return f"特征 {key} 必须是有限数字"
    return None


def build_features(data, requested_features=None):
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
    data = data or {}
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

    values = {
        "home_elo": h_elo, "away_elo": a_elo, "elo_diff": elo_diff,
        "home_win_rate": h_wr, "away_win_rate": a_wr,
        "home_avg_goals": h_ag, "away_avg_goals": a_ag,
        "home_avg_loss": h_al, "away_avg_loss": a_al,
        "home_avg_cards": h_ac, "away_avg_cards": a_ac,
        "home_days_rest": h_dr, "away_days_rest": a_dr,
        "h2h_home_wins": h2h_hw, "h2h_draws": h2h_d, "h2h_away_wins": h2h_aw,
        "home_win_rate_diff": wr_diff, "elo_sum": elo_sum,
        "home_goal_diff": goal_diff, "avg_total_goals": total_goals,
        "rank_diff": float(data.get("home_rank", 0) or 0) - float(data.get("away_rank", 0) or 0),
    }
    for key in ADVANCED_FEATURE_KEYS:
        if key not in values:
            values[key] = float(data.get(key, 0.0) or 0.0)
    # The active bundle declares its own feature order. This avoids the common
    # production failure where Java sends a new field set to an old model (or
    # vice versa) and silently shifts every column.
    requested = requested_features or feature_names or LEGACY_FEATURE_KEYS
    features = [
        float(values.get(key, data.get(key, 0.0)) or 0.0) for key in requested
    ]
    return np.array(features, dtype=np.float64).reshape(1, -1)


def prediction_metadata(probabilities, threshold=None):
    """返回判定强度，不把最大类别概率冒充校准置信度。"""
    values = sorted([float(v) for v in probabilities], reverse=True)
    margin = max(0.0, values[0] - values[1]) if len(values) > 1 else 0.0
    # Older active bundles predate the abstention metadata. Use a conservative
    # neutral default until a validation-derived threshold is available.
    threshold = float(model_metrics.get("abstain_threshold", 0.45) if threshold is None else threshold)
    if threshold <= 0:
        threshold = 0.45
    low_confidence = threshold > 0 and values[0] < threshold
    return {
        "decisionMargin": round(margin, 4),
        "confidenceType": "decision-margin",
        "confidenceLabel": "倾向明确" if margin >= 0.20 else ("存在分歧" if margin >= 0.08 else "接近均势"),
        "recommendation": "low-confidence" if low_confidence else ("normal" if margin >= 0.08 else "cautious"),
        "confidenceStatus": "LOW_CONFIDENCE" if low_confidence else "CALIBRATED",
        "abstainThreshold": round(threshold, 3)
    }


def temperature_scale(probabilities, temperature):
    values = np.clip(np.asarray(probabilities, dtype=float), 1e-6, 1.0)
    logits = np.log(values) / max(float(temperature), 1e-3)
    logits = logits - np.max(logits)
    exp_logits = np.exp(logits)
    return exp_logits / np.sum(exp_logits)


def class_bias_scale(probabilities, bias):
    values = np.clip(np.asarray(probabilities, dtype=float), 1e-6, 1.0)
    offsets = np.asarray(bias or [0.0, 0.0, 0.0], dtype=float)
    if offsets.size != 3:
        offsets = np.zeros(3, dtype=float)
    logits = np.log(values) + offsets
    logits -= np.max(logits)
    exp_logits = np.exp(logits)
    return exp_logits / np.sum(exp_logits)


LEAGUE_ALIASES = {
    "39": "PL", "pl": "PL", "英超": "PL", "premierleague": "PL",
    "78": "BL1", "bl1": "BL1", "德甲": "BL1", "bundesliga": "BL1",
    "140": "PD", "pd": "PD", "西甲": "PD", "laliga": "PD", "primeradivision": "PD",
    "61": "FL1", "fl1": "FL1", "法甲": "FL1", "ligue1": "FL1",
    "135": "SA", "sa": "SA", "意甲": "SA", "seriea": "SA",
    "88": "DED", "ded": "DED", "荷甲": "DED", "eredivisie": "DED",
    "94": "PPL", "ppl": "PPL", "葡超": "PPL", "primeiraliga": "PPL",
    "40": "ELC", "elc": "ELC", "英冠": "ELC", "championship": "ELC",
}


def normalize_league_code(data):
    """Resolve provider ids/names to a stable routing key."""
    data = data or {}
    for value in (data.get("league_id"), data.get("leagueId"), data.get("league_name"), data.get("leagueName")):
        if value is None:
            continue
        raw = str(value).strip().lower()
        compact = "".join(ch for ch in raw if ch.isalnum() or "\u4e00" <= ch <= "\u9fff")
        if raw in LEAGUE_ALIASES:
            return LEAGUE_ALIASES[raw]
        if compact in LEAGUE_ALIASES:
            return LEAGUE_ALIASES[compact]
        if raw.startswith("bbc-"):
            for key, code in LEAGUE_ALIASES.items():
                if key and key in raw:
                    return code
    return ""


def bundle_prediction(data, bundle, metrics, route):
    """Run a specialist/global bundle with its own feature order and weights."""
    requested = bundle.get("feature_names") or feature_names
    features = build_features(data, requested)
    xgb_model = bundle.get("xgb")
    logistic_model = bundle.get("logistic")
    bundle_scaler = bundle.get("scaler")
    xgb_probs = xgb_model.predict_proba(features)[0] if xgb_model is not None else np.array([1 / 3] * 3)
    logistic_probs = logistic_model.predict_proba(bundle_scaler.transform(features))[0] if logistic_model is not None and bundle_scaler is not None else xgb_probs
    xgb_weight = float(bundle.get("blend_weight_xgboost", 0.5))
    model_probs = xgb_weight * xgb_probs + (1 - xgb_weight) * logistic_probs
    elo_weight = float(bundle.get("blend_weight_elo", 0.0))
    model_weight = float(bundle.get("blend_weight_model", 1.0 - elo_weight))
    poisson_weight = float(bundle.get("blend_weight_poisson", 0.0))
    probs = (elo_weight * elo_probabilities(features)
             + poisson_weight * poisson_probabilities(features)
             + model_weight * model_probs)
    probs = np.asarray(probs[0] if getattr(probs, "ndim", 1) > 1 else probs, dtype=float)
    probs = temperature_scale(probs, bundle.get("calibration_temperature", metrics.get("calibration_temperature", 1.0)))
    probs = class_bias_scale(probs, bundle.get("class_bias", metrics.get("class_bias", [0.0, 0.0, 0.0])))
    home_win_prob, draw_prob, away_win_prob = [float(value) for value in probs]
    result_label = "HOME_WIN" if home_win_prob >= draw_prob and home_win_prob >= away_win_prob else ("AWAY_WIN" if away_win_prob >= home_win_prob else "DRAW")
    return {
        "homeWinProb": round(home_win_prob, 4), "drawProb": round(draw_prob, 4), "awayWinProb": round(away_win_prob, 4),
        "resultLabel": result_label,
        "modelVersion": metrics.get("strategy", bundle.get("strategy", route)),
        "modelRoute": route,
        "explanation": f"{route}：主队胜率{home_win_prob*100:.1f}%，平局概率{draw_prob*100:.1f}%，客队胜率{away_win_prob*100:.1f}%。",
        "topFeatures": build_top_features(data), "modelQuality": metrics,
        "qualityGate": {"eligible": True, "route": route, "checks": (metrics.get("promotion") or {}).get("checks", {})},
        **prediction_metadata([home_win_prob, draw_prob, away_win_prob], bundle.get("abstain_threshold", metrics.get("abstain_threshold", 0.45)))
    }


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


def elo_probabilities(features):
    elo_diff = features[:, 2]
    home = 1 / (1 + 10 ** (-elo_diff / 400))
    draw = np.clip(0.26 - np.abs(elo_diff) / 2400, 0.08, 0.28)
    probabilities = np.column_stack([home * (1 - draw), draw, (1 - home) * (1 - draw)])
    return probabilities / probabilities.sum(axis=1, keepdims=True)


def poisson_probabilities(features):
    home_lambda = np.clip(0.58 * features[:, 5] + 0.42 * features[:, 8] + 0.12, 0.2, 4.5)
    away_lambda = np.clip(0.58 * features[:, 6] + 0.42 * features[:, 7], 0.15, 4.0)
    probabilities = []
    for home_mean, away_mean in zip(home_lambda, away_lambda):
        home_goals = np.array([np.exp(-home_mean) * home_mean ** k / math.factorial(k) for k in range(9)])
        away_goals = np.array([np.exp(-away_mean) * away_mean ** k / math.factorial(k) for k in range(9)])
        matrix = np.outer(home_goals, away_goals)
        row = np.array([np.tril(matrix, -1).sum(), np.trace(matrix), np.triu(matrix, 1).sum()])
        probabilities.append(row / max(row.sum(), 1e-8))
    return np.asarray(probabilities)


def production_quality_gate():
    """Return the explicit production gate used by both API and UI.

    A calibrated probability is not enough when the model is no better than
    ELO or never recognizes draws.  In that case we keep a transparent ELO
    fallback and expose the reason instead of presenting a misleading model
    result as production-grade.
    """
    accuracy = float(model_metrics.get("accuracy", 0.0) or 0.0)
    baseline_accuracy = float(model_metrics.get("baseline_accuracy", 1.0) or 1.0)
    balanced = float(model_metrics.get("balanced_accuracy", 0.0) or 0.0)
    class_recall = model_metrics.get("class_recall") or {}
    draw_recall = float(class_recall.get("DRAW", 0.0) or 0.0)
    stability = model_metrics.get("stability") or {}
    promotion = model_metrics.get("promotion") or {}
    checks = {
        # Reports produced before the current gate rules are never eligible,
        # even if their historical JSON says accepted=true.
        "promotionGateVersion": int(model_metrics.get("production_gate_version", 0) or 0) >= 2
                and int(promotion.get("gateVersion", 0) or 0) >= 2,
        "promotionAccepted": bool(promotion.get("accepted") is True),
        "accuracyEdge": accuracy >= baseline_accuracy + 0.005,
        "balancedAccuracy": balanced >= 0.50,
        "drawRecall": draw_recall >= 0.15,
        "stability": bool(stability.get("passed") is True),
    }
    return {
        "eligible": all(checks.values()),
        "checks": checks,
        "message": "模型通过生产门槛" if all(checks.values()) else "当前模型未显著超过基线或平局识别不足，使用透明ELO兜底",
    }


def baseline_prediction(data):
    """Point-in-time ELO + Poisson fallback when the promoted model is absent."""
    features = build_features(data)
    elo = elo_probabilities(features)[0]
    poisson = poisson_probabilities(features)[0]
    probabilities = 0.62 * elo + 0.38 * poisson
    probabilities = probabilities / max(float(probabilities.sum()), 1e-8)
    h_prob, d_prob, a_prob = [float(value) for value in probabilities]
    h_elo = float(data.get("home_elo", 1500.0))
    a_elo = float(data.get("away_elo", 1500.0))

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
        "modelVersion": "baseline-elo-poisson-v2",
        "modelRoute": "global-generic" if normalize_league_code(data) not in SPECIALIST_CODES else "global-fallback",
        "explanation": explanation,
        "topFeatures": build_top_features(data),
        "modelQuality": model_metrics,
        "qualityGate": production_quality_gate(),
        **prediction_metadata([h_prob, d_prob, a_prob])
    }


@app.route("/health", methods=["GET"])
def health():
    candidate_promotion = {"accepted": None, "decision": "UNKNOWN"}
    if os.path.exists(CANDIDATE_TRAIN_RESULTS_PATH):
        try:
            with open(CANDIDATE_TRAIN_RESULTS_PATH, "r", encoding="utf-8") as candidate_file:
                candidate_promotion = json.load(candidate_file).get("promotion", candidate_promotion)
        except Exception:
            pass
    active_strategy = model_metrics.get("strategy")
    if not active_strategy and hybrid_model is not None:
        active_strategy = hybrid_model.get("strategy", "hybrid-xgb-logreg-elo-poisson-v3")
    return jsonify({"status": "ok", "model_ready": model_ready, "hybrid_ready": hybrid_model is not None,
                    "model_metrics": model_metrics,
                    "qualityGate": production_quality_gate(),
                    "modelRegistry": {"active": active_strategy or "baseline-elo-poisson-v2",
                                       "rollbackAvailable": os.path.exists(os.path.join(MODEL_DIR, "hybrid_model.previous.joblib")),
                                       "candidateAvailable": os.path.exists(os.path.join(MODEL_DIR, "hybrid_model.candidate.joblib")),
                                       "promotion": model_metrics.get("promotion", candidate_promotion),
                                       "specialists": {
                                           code: {
                                               "loaded": code in specialist_models,
                                               "promotion": (specialist_metrics.get(code) or {}).get("promotion", {}),
                                               "accuracy": (specialist_metrics.get(code) or {}).get("accuracy"),
                                               "baselineAccuracy": (specialist_metrics.get(code) or {}).get("baseline_accuracy"),
                                           } for code in SPECIALIST_CODES
                                       }}})


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
    auth_error = require_internal_auth()
    if auth_error is not None:
        return auth_error
    try:
        data = request.get_json(silent=True)
    except Exception:
        return jsonify({"error": "Invalid JSON"}), 400
    payload_error = validate_prediction_payload(data)
    if payload_error:
        return jsonify({"error": payload_error}), 400

    # Route only the five leagues with an accepted specialist to their own
    # bundle.荷甲、葡超、英冠 intentionally fall through to the global/baseline
    # model and remain fully predictable.
    specialist_code = normalize_league_code(data)
    specialist = specialist_models.get(specialist_code)
    if specialist is not None:
        try:
            return jsonify(bundle_prediction(data, specialist, specialist_metrics.get(specialist_code, {}),
                                            f"league-specialist-{specialist_code}"))
        except Exception as exc:
            print(f"[ML] Specialist prediction error ({specialist_code}), falling back: {exc}", file=sys.stderr)

    if hybrid_model is not None and production_quality_gate()["eligible"]:
        try:
            features = build_features(data)
            xgb_model = hybrid_model["xgb"]
            logistic_model = hybrid_model["logistic"]
            hybrid_scaler = hybrid_model["scaler"]
            xgb_probs = xgb_model.predict_proba(features)[0]
            logistic_probs = logistic_model.predict_proba(hybrid_scaler.transform(features))[0]
            xgb_weight = float(hybrid_model.get("blend_weight_xgboost", 0.5))
            catboost_model = hybrid_model.get("catboost")
            catboost_weight = float(hybrid_model.get("blend_weight_catboost", 0.0))
            model_weight = float(hybrid_model.get("blend_weight_model", 1.0))
            elo_weight = float(hybrid_model.get("blend_weight_elo", 0.0))
            poisson_weight = float(hybrid_model.get("blend_weight_poisson", 0.0))
            model_probs = xgb_weight * xgb_probs + (1 - xgb_weight) * logistic_probs
            if catboost_model is not None and catboost_weight > 0:
                catboost_probs = catboost_model.predict_proba(features)[0]
                model_probs = catboost_weight * catboost_probs + (1 - catboost_weight) * model_probs
            probs = (elo_weight * elo_probabilities(features)[0]
                     + poisson_weight * poisson_probabilities(features)[0]
                     + model_weight * model_probs)
            probs = temperature_scale(probs, model_metrics.get("calibration_temperature", 1.0))
            probs = class_bias_scale(probs, hybrid_model.get("class_bias", model_metrics.get("class_bias", [0.0, 0.0, 0.0])))
            home_win_prob, draw_prob, away_win_prob = [float(value) for value in probs]
            if home_win_prob >= draw_prob and home_win_prob >= away_win_prob:
                result_label = "HOME_WIN"
            elif away_win_prob >= home_win_prob:
                result_label = "AWAY_WIN"
            else:
                result_label = "DRAW"
            return jsonify({
                "homeWinProb": round(home_win_prob, 4),
                "drawProb": round(draw_prob, 4),
                "awayWinProb": round(away_win_prob, 4),
                "resultLabel": result_label,
                "modelVersion": model_metrics.get("strategy", hybrid_model.get("strategy", "hybrid-xgb-logreg-elo-poisson-v3")),
                "explanation": (
                    (f"校准 ELO 预测：主队胜率{home_win_prob*100:.1f}%，平局概率{draw_prob*100:.1f}%，"
                     f"客队胜率{away_win_prob*100:.1f}%。模型采用时间滚动验证，并在上线前通过稳定性门槛。"
                     if model_metrics.get("strategy", hybrid_model.get("strategy")) == "elo-calibrated-v3" else
                     f"混合模型预测：主队胜率{home_win_prob*100:.1f}%，平局概率{draw_prob*100:.1f}%，"
                     f"客队胜率{away_win_prob*100:.1f}%。结果融合 XGBoost、逻辑回归、ELO 与进球分布基线，"
                     f"权重按时间滚动验证集自动选择。")
                ),
                "topFeatures": build_top_features(data),
                "modelQuality": model_metrics,
                "qualityGate": production_quality_gate(),
                **prediction_metadata([home_win_prob, draw_prob, away_win_prob], hybrid_model.get("abstain_threshold"))
            })
        except Exception as e:
            print(f"[ML] Hybrid prediction error, falling back: {e}", file=sys.stderr)

    if hybrid_model is not None and not production_quality_gate()["eligible"]:
        return baseline_prediction(data)

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
        probs = temperature_scale(probs, model_metrics.get("calibration_temperature", 1.0))
        probs = class_bias_scale(probs, model_metrics.get("class_bias", [0.0, 0.0, 0.0]))
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
            "topFeatures": build_top_features(data),
            "modelQuality": model_metrics,
            "qualityGate": production_quality_gate(),
            **prediction_metadata([home_win_prob, draw_prob, away_win_prob])
        })

    except Exception as e:
        print(f"[ML] Prediction error: {e}", file=sys.stderr)
        return jsonify({"error": "模型推理失败，请检查特征契约或服务日志"}), 500


@app.route("/reload", methods=["POST"])
def reload_model():
    """Reload the trained model."""
    auth_error = require_internal_auth()
    if auth_error is not None:
        return auth_error
    global model, scaler, model_ready
    load_model()
    return jsonify({"model_ready": model_ready})


@app.route("/admin/rollback", methods=["POST"])
def rollback_model():
    """回滚到上一版模型；必须显式配置 ML_ADMIN_TOKEN，默认关闭。"""
    auth_error = require_internal_auth()
    if auth_error is not None:
        return auth_error
    if not ML_ADMIN_TOKEN or request.headers.get("X-ML-Admin-Token", "") != ML_ADMIN_TOKEN:
        return jsonify({"ok": False, "message": "model rollback is disabled or unauthorized"}), 403
    if not os.path.exists(PREVIOUS_HYBRID_MODEL_PATH) or not os.path.exists(PREVIOUS_TRAIN_RESULTS_PATH):
        return jsonify({"ok": False, "message": "no rollback artifact available"}), 409
    try:
        shutil.copy2(HYBRID_MODEL_PATH, HYBRID_MODEL_PATH + ".before-rollback")
        shutil.copy2(TRAIN_RESULTS_PATH, TRAIN_RESULTS_PATH + ".before-rollback")
        shutil.copy2(PREVIOUS_HYBRID_MODEL_PATH, HYBRID_MODEL_PATH)
        shutil.copy2(PREVIOUS_TRAIN_RESULTS_PATH, TRAIN_RESULTS_PATH)
        load_model()
        return jsonify({"ok": True, "model_ready": model_ready, "modelRegistry": {"active": "previous", "rollbackAvailable": True}})
    except Exception as exc:
        print(f"[ML] Rollback error: {exc}", file=sys.stderr)
        return jsonify({"ok": False, "message": "模型回滚失败，请检查服务日志"}), 500


if __name__ == "__main__":
    port = int(os.environ.get("PORT", 5001))
    app.run(host="0.0.0.0", port=port, debug=False)
