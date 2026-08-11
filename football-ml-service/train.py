"""
XGBoost 足球比赛结果预测模型训练脚本

用法:
  python train.py

训练完成后会在 models/ 目录下生成:
  - xgboost_model.json   : 训练好的 XGBoost 模型
  - feature_scaler.joblib : 特征标准化器
  - feature_names.txt     : 特征名称列表
  - train_results.json    : 训练评估报告
"""

import os
import sys
import json
import joblib
import warnings
warnings.filterwarnings("ignore")

import numpy as np
import pandas as pd
import requests
from datetime import datetime, timedelta
from sklearn.preprocessing import StandardScaler
from sklearn.model_selection import train_test_split, cross_val_score
from sklearn.metrics import (
    accuracy_score, precision_score, recall_score,
    f1_score, classification_report, confusion_matrix
)
import xgboost as xgb

# ==================== 配置 ====================
TRAIN_DATA_SOURCE = os.environ.get("TRAIN_DATA_SOURCE", "football-data").strip().lower()
API_KEY = os.environ.get("API_FOOTBALL_API_KEY", "").strip()
BASE_URL = "https://v3.football.api-sports.io"
HEADERS = {"x-apisports-key": API_KEY}

FOOTBALL_DATA_API_KEY = os.environ.get("FOOTBALL_DATA_API_KEY", "713cc15a646f47a8b66f3db70d3d0623").strip()
FOOTBALL_DATA_BASE_URL = "https://api.football-data.org/v4"
FOOTBALL_DATA_HEADERS = {"X-Auth-Token": FOOTBALL_DATA_API_KEY}

DEFAULT_LEAGUES = {
    39: "Premier League",
    140: "La Liga",
    135: "Serie A",
    78: "Bundesliga",
    61: "Ligue 1",
}

FOOTBALL_DATA_COMPETITIONS = {
    "WC": "FIFA World Cup",
    "CL": "UEFA Champions League",
    "BL1": "Bundesliga",
    "DED": "Eredivisie",
    "BSA": "Campeonato Brasileiro Série A",
    "PD": "Primera Division",
    "FL1": "Ligue 1",
    "ELC": "Championship",
    "PPL": "Primeira Liga",
    "EC": "European Championship",
    "SA": "Serie A",
    "PL": "Premier League",
}


def parse_int_list(value: str | None, default: list[int]) -> list[int]:
    """解析逗号分隔的整数环境变量。"""
    if not value:
        return default
    result = []
    for item in value.split(","):
        item = item.strip()
        if item:
            result.append(int(item))
    return result or default


def parse_str_list(value: str | None, default: list[str]) -> list[str]:
    """解析逗号分隔的字符串环境变量。"""
    if not value:
        return default
    result = []
    for item in value.split(","):
        item = item.strip().upper()
        if item:
            result.append(item)
    return result or default


TRAIN_LEAGUE_IDS = parse_int_list(os.environ.get("TRAIN_LEAGUES"), [39])
FOOTBALL_DATA_CODES = parse_str_list(
    os.environ.get("FOOTBALL_DATA_COMPETITIONS"),
    ["PL", "BL1", "PD", "FL1", "SA", "DED", "PPL", "ELC"]
)
TRAIN_SEASONS = parse_int_list(os.environ.get("TRAIN_SEASONS"), [2023])
MAX_MATCHES_PER_SEASON = int(os.environ.get("MAX_MATCHES_PER_SEASON", 380))
MIN_REAL_RECORDS = int(os.environ.get("MIN_REAL_RECORDS", 500))
ALLOW_SYNTHETIC_DATA = os.environ.get("ALLOW_SYNTHETIC_DATA", "false").lower() in {"1", "true", "yes", "y"}
SYNTHETIC_SAMPLES = int(os.environ.get("SYNTHETIC_SAMPLES", 3000))

MYSQL_CONFIG = {
    "host": os.environ.get("MYSQL_HOST", "127.0.0.1"),
    "port": int(os.environ.get("MYSQL_PORT", 3307)),
    "user": os.environ.get("MYSQL_USER", "root"),
    "password": os.environ.get("MYSQL_PASSWORD", "root"),
    "database": os.environ.get("MYSQL_DB", "football_forecast"),
    "charset": "utf8mb4"
}

MODEL_DIR = os.path.join(os.path.dirname(__file__), "models")
os.makedirs(MODEL_DIR, exist_ok=True)

FEATURE_NAMES = [
    "home_elo", "away_elo", "elo_diff",
    "home_win_rate", "away_win_rate",
    "home_avg_goals", "away_avg_goals",
    "home_avg_loss", "away_avg_loss",
    "home_avg_cards", "away_avg_cards",
    "home_days_rest", "away_days_rest",
    "h2h_home_wins", "h2h_draws", "h2h_away_wins",
    "home_win_rate_diff", "elo_sum",
    "home_goal_diff", "avg_total_goals"
]

# ==================== 数据获取 ====================

def print_api_errors(endpoint: str, data: dict) -> None:
    """打印 API-Football 返回的错误信息，避免静默失败。"""
    errors = data.get("errors")
    if errors:
        print(f"[API ERROR] {endpoint}: {errors}")


def fetch_fixtures(league_id: int, season: int, total_pages: int = 10) -> list:
    """从 API-Football 拉取指定联赛、赛季的历史比赛"""
    all_fixtures = []
    for page in range(1, total_pages + 1):
        params = {
            "league": league_id,
            "season": season,
            "status": "FT"  # 只拉已完成比赛
        }
        if page > 1:
            params["page"] = page
        try:
            resp = requests.get(f"{BASE_URL}/fixtures", headers=HEADERS, params=params, timeout=15)
            data = resp.json()
            print_api_errors("fixtures", data)
            if data.get("response"):
                all_fixtures.extend(data["response"])
            if not data.get("paging") or page >= data["paging"]["total"]:
                break
        except Exception as e:
            print(f"[WARN] Page {page} failed: {e}")
    return all_fixtures


def fetch_team_stats(team_id: int, league_id: int, season: int) -> dict:
    """获取球队赛季统计"""
    params = {"team": team_id, "league": league_id, "season": season}
    try:
        resp = requests.get(f"{BASE_URL}/teams/statistics", headers=HEADERS, params=params, timeout=10)
        data = resp.json()
        print_api_errors("teams/statistics", data)
        if data.get("response"):
            return data["response"]
    except Exception as e:
        print(f"[WARN] Team stats failed for team={team_id}, league={league_id}, season={season}: {e}")
    return {}


def extract_fixture_features(fixture: dict, home_stats: dict, away_stats: dict) -> dict | None:
    """从一个 fixture 提取模型特征"""
    try:
        goals = fixture.get("goals", {})
        if not goals or goals.get("home") is None:
            return None

        home_goals = int(goals["home"])
        away_goals = int(goals["away"])

        # 标签: 0=主胜, 1=平, 2=客胜
        if home_goals > away_goals:
            label = 0
        elif home_goals < away_goals:
            label = 2
        else:
            label = 1

        # 从球队统计提取特征
        def safe_float(d, *keys, default=0.0):
            v = d
            for k in keys:
                if isinstance(v, dict):
                    v = v.get(k, default)
                else:
                    return default
            try:
                return float(v)
            except (TypeError, ValueError):
                return default

        def safe_int(d, *keys, default=0):
            v = d
            for k in keys:
                if isinstance(v, dict):
                    v = v.get(k, default)
                else:
                    return default
            try:
                return int(v)
            except (TypeError, ValueError):
                return default

        h_stats = home_stats.get("statistics", {})
        a_stats = away_stats.get("statistics", {})

        home_played = safe_int(h_stats, "matches", "played", "total", default=0)
        away_played = safe_int(a_stats, "matches", "played", "total", default=0)

        home_wins = safe_int(h_stats, "matches", "win", "total", default=0)
        away_wins = safe_int(a_stats, "matches", "win", "total", default=0)
        home_draws = safe_int(h_stats, "matches", "draw", "total", default=0)
        away_draws = safe_int(a_stats, "matches", "draw", "total", default=0)
        home_loss = safe_int(h_stats, "matches", "lose", "total", default=0)
        away_loss = safe_int(a_stats, "matches", "lose", "total", default=0)

        home_goals_f = safe_float(h_stats, "goals", "for", "total", "total", default=0.0)
        away_goals_f = safe_float(a_stats, "goals", "for", "total", "total", default=0.0)
        home_goals_a = safe_float(h_stats, "goals", "against", "total", "total", default=0.0)
        away_goals_a = safe_float(a_stats, "goals", "against", "total", "total", default=0.0)

        home_cards = safe_float(h_stats, "cards", "yellow", "total", default=0.0)
        away_cards = safe_float(a_stats, "cards", "yellow", "total", default=0.0)

        # 计算速率
        home_wr = home_wins / home_played if home_played > 0 else 0.0
        away_wr = away_wins / away_played if away_played > 0 else 0.0
        home_ag = home_goals_f / home_played if home_played > 0 else 0.0
        away_ag = away_goals_f / away_played if away_played > 0 else 0.0
        home_al = home_goals_a / home_played if home_played > 0 else 0.0
        away_al = away_goals_a / away_played if away_played > 0 else 0.0
        home_ac = home_cards / home_played if home_played > 0 else 0.0
        away_ac = away_cards / away_played if away_played > 0 else 0.0

        # ELO (用积分榜模拟)
        home_pts = home_wins * 3 + home_draws
        away_pts = away_wins * 3 + away_draws
        home_elo = 1500 + (home_pts - away_pts / 2) * 3 if home_played > 0 else 1500.0
        away_elo = 1500 + (away_pts - home_pts / 2) * 3 if away_played > 0 else 1500.0

        # 休整天数（模拟）
        home_days_rest = 7
        away_days_rest = 7

        # 历史交锋（模拟：取最近5场的统计）
        total_h2h = 5
        h2h_hw = int(total_h2h * 0.4)
        h2h_d = int(total_h2h * 0.2)
        h2h_aw = total_h2h - h2h_hw - h2h_d

        return {
            "home_elo": round(home_elo, 2),
            "away_elo": round(away_elo, 2),
            "elo_diff": round(home_elo - away_elo, 2),
            "home_win_rate": round(home_wr, 4),
            "away_win_rate": round(away_wr, 4),
            "home_avg_goals": round(home_ag, 4),
            "away_avg_goals": round(away_ag, 4),
            "home_avg_loss": round(home_al, 4),
            "away_avg_loss": round(away_al, 4),
            "home_avg_cards": round(home_ac, 4),
            "away_avg_cards": round(away_ac, 4),
            "home_days_rest": home_days_rest,
            "away_days_rest": away_days_rest,
            "h2h_home_wins": h2h_hw,
            "h2h_draws": h2h_d,
            "h2h_away_wins": h2h_aw,
            "home_win_rate_diff": round(home_wr - away_wr, 4),
            "elo_sum": round(home_elo + away_elo, 2),
            "home_goal_diff": round(home_ag - away_ag, 4),
            "avg_total_goals": round((home_ag + away_ag) / 2, 4),
            "label": label
        }
    except Exception as e:
        print(f"[WARN] extract_features failed: {e}")
        return None


def fetch_football_data_matches(competition_code: str, season: int) -> list:
    """从 football-data.org 拉取指定赛事、赛季的比赛列表。"""
    params = {"season": season}
    url = f"{FOOTBALL_DATA_BASE_URL}/competitions/{competition_code}/matches"
    try:
        resp = requests.get(url, headers=FOOTBALL_DATA_HEADERS, params=params, timeout=20)
        data = resp.json()
        if resp.status_code >= 400:
            print(f"[API ERROR] football-data {competition_code} {season}: {data}")
            return []
        return data.get("matches", [])
    except Exception as e:
        print(f"[WARN] football-data fetch failed for {competition_code} {season}: {e}")
        return []


def build_football_data_features(matches: list, max_matches: int | None = None) -> list[dict]:
    """基于 football-data.org 比赛列表滚动构建训练特征。"""
    completed = []
    for match in matches:
        score = match.get("score", {})
        full_time = score.get("fullTime", {})
        home_goals = full_time.get("home")
        away_goals = full_time.get("away")
        if match.get("status") != "FINISHED" or home_goals is None or away_goals is None:
            continue
        completed.append(match)

    completed.sort(key=lambda item: item.get("utcDate", ""))
    if max_matches and max_matches > 0:
        completed = completed[:max_matches]

    team_stats: dict[int, list[dict]] = {}
    last_played: dict[int, datetime] = {}
    elo: dict[int, float] = {}
    h2h_stats: dict[tuple[int, int], list[int]] = {}
    records = []

    def get_elo(team_id: int) -> float:
        if team_id not in elo:
            elo[team_id] = 1500.0
        return elo[team_id]

    def update_elo(home_id: int, away_id: int, label: int, k: int = 28) -> None:
        home_elo = get_elo(home_id)
        away_elo = get_elo(away_id)
        expected_home = 1 / (1 + 10 ** ((away_elo - home_elo) / 400))
        if label == 0:
            actual_home = 1.0
        elif label == 1:
            actual_home = 0.5
        else:
            actual_home = 0.0
        elo[home_id] = home_elo + k * (actual_home - expected_home)
        elo[away_id] = away_elo + k * ((1 - actual_home) - (1 - expected_home))

    def calc_team(team_id: int, match_dt: datetime) -> dict:
        recent = team_stats.get(team_id, [])[-10:]
        if not recent:
            return {
                "win_rate": 0.45,
                "avg_goals": 1.5,
                "avg_loss": 1.2,
                "avg_cards": 1.5,
                "days_rest": 7,
            }
        n = len(recent)
        wins = sum(1 for item in recent if item["result"] == 0)
        draws = sum(1 for item in recent if item["result"] == 1)
        goals = sum(item["goals"] for item in recent)
        conceded = sum(item["conceded"] for item in recent)
        previous_dt = last_played.get(team_id)
        days_rest = 7 if previous_dt is None else max(1, min(30, (match_dt - previous_dt).days))
        return {
            "win_rate": (wins + draws * 0.5) / n,
            "avg_goals": goals / n,
            "avg_loss": conceded / n,
            "avg_cards": 1.5,
            "days_rest": days_rest,
        }

    for match in completed:
        home = match.get("homeTeam", {})
        away = match.get("awayTeam", {})
        home_id = home.get("id")
        away_id = away.get("id")
        if home_id is None or away_id is None:
            continue

        score = match.get("score", {}).get("fullTime", {})
        home_goals = int(score["home"])
        away_goals = int(score["away"])
        if home_goals > away_goals:
            label = 0
        elif home_goals == away_goals:
            label = 1
        else:
            label = 2

        match_dt = datetime.fromisoformat(match["utcDate"].replace("Z", "+00:00")).replace(tzinfo=None)
        home_feat = calc_team(home_id, match_dt)
        away_feat = calc_team(away_id, match_dt)
        home_elo = get_elo(home_id) + 35
        away_elo = get_elo(away_id)
        pair_key = tuple(sorted((home_id, away_id)))
        h2h = h2h_stats.get(pair_key, [])[-5:]
        h2h_home_wins = sum(1 for item in h2h if item == home_id)
        h2h_draws = sum(1 for item in h2h if item == 0)
        h2h_away_wins = sum(1 for item in h2h if item == away_id)

        records.append({
            "home_elo": round(home_elo, 2),
            "away_elo": round(away_elo, 2),
            "elo_diff": round(home_elo - away_elo, 2),
            "home_win_rate": round(home_feat["win_rate"], 4),
            "away_win_rate": round(away_feat["win_rate"], 4),
            "home_avg_goals": round(home_feat["avg_goals"], 4),
            "away_avg_goals": round(away_feat["avg_goals"], 4),
            "home_avg_loss": round(home_feat["avg_loss"], 4),
            "away_avg_loss": round(away_feat["avg_loss"], 4),
            "home_avg_cards": round(home_feat["avg_cards"], 4),
            "away_avg_cards": round(away_feat["avg_cards"], 4),
            "home_days_rest": home_feat["days_rest"],
            "away_days_rest": away_feat["days_rest"],
            "h2h_home_wins": h2h_home_wins,
            "h2h_draws": h2h_draws,
            "h2h_away_wins": h2h_away_wins,
            "home_win_rate_diff": round(home_feat["win_rate"] - away_feat["win_rate"], 4),
            "elo_sum": round(home_elo + away_elo, 2),
            "home_goal_diff": round(home_feat["avg_goals"] - away_feat["avg_goals"], 4),
            "avg_total_goals": round((home_feat["avg_goals"] + away_feat["avg_goals"]) / 2, 4),
            "label": label,
        })

        team_stats.setdefault(home_id, []).append({"result": label, "goals": home_goals, "conceded": away_goals})
        team_stats.setdefault(away_id, []).append({"result": 2 - label if label != 1 else 1, "goals": away_goals, "conceded": home_goals})
        last_played[home_id] = match_dt
        last_played[away_id] = match_dt
        if label == 0:
            h2h_stats.setdefault(pair_key, []).append(home_id)
        elif label == 2:
            h2h_stats.setdefault(pair_key, []).append(away_id)
        else:
            h2h_stats.setdefault(pair_key, []).append(0)
        update_elo(home_id, away_id, label)

    return records


def generate_synthetic_data(n_samples: int = 2000) -> pd.DataFrame:
    """
    当 API 数据不足时，生成模拟数据用于训练演示
    基于真实足球分布规律
    """
    np.random.seed(42)
    records = []

    for _ in range(n_samples):
        home_elo = np.random.normal(1550, 150)
        away_elo = np.random.normal(1450, 150)

        elo_diff = home_elo - away_elo
        # ELO 预测胜率
        home_wr_true = 1 / (1 + 10 ** (-elo_diff / 400))
        away_wr_true = 1 / (1 + 10 ** (elo_diff / 400))
        draw_prob = max(0.05, 0.28 - abs(elo_diff) / 3000)

        home_wr = home_wr_true + np.random.normal(0, 0.05)
        away_wr = away_wr_true + np.random.normal(0, 0.05)

        home_ag = np.random.normal(1.6, 0.5)
        away_ag = np.random.normal(1.3, 0.5)
        home_al = np.random.normal(1.2, 0.4)
        away_al = np.random.normal(1.3, 0.4)
        home_ac = np.random.normal(2.0, 0.8)
        away_ac = np.random.normal(2.0, 0.8)

        # 主场优势加成
        home_elo += 50
        elo_diff = home_elo - away_elo

        home_days_rest = np.random.choice([3, 5, 7, 10, 14], p=[0.1, 0.2, 0.35, 0.25, 0.1])
        away_days_rest = np.random.choice([3, 5, 7, 10, 14], p=[0.1, 0.2, 0.35, 0.25, 0.1])

        # 历史交锋
        total_h2h = np.random.randint(3, 12)
        h2h_hw = np.random.randint(0, total_h2h)
        h2h_d = np.random.randint(0, total_h2h - h2h_hw)
        h2h_aw = total_h2h - h2h_hw - h2h_d

        # 模拟标签
        r = np.random.random()
        if r < home_wr_true:
            label = 0  # HOME_WIN
        elif r < home_wr_true + draw_prob:
            label = 1  # DRAW
        else:
            label = 2  # AWAY_WIN

        records.append({
            "home_elo": round(home_elo, 2),
            "away_elo": round(away_elo, 2),
            "elo_diff": round(elo_diff, 2),
            "home_win_rate": round(max(0, min(1, home_wr)), 4),
            "away_win_rate": round(max(0, min(1, away_wr)), 4),
            "home_avg_goals": round(max(0, home_ag), 4),
            "away_avg_goals": round(max(0, away_ag), 4),
            "home_avg_loss": round(max(0, home_al), 4),
            "away_avg_loss": round(max(0, away_al), 4),
            "home_avg_cards": round(max(0, home_ac), 4),
            "away_avg_cards": round(max(0, away_ac), 4),
            "home_days_rest": home_days_rest,
            "away_days_rest": away_days_rest,
            "h2h_home_wins": h2h_hw,
            "h2h_draws": h2h_d,
            "h2h_away_wins": h2h_aw,
            "home_win_rate_diff": round(max(0, min(1, home_wr)) - max(0, min(1, away_wr)), 4),
            "elo_sum": round(home_elo + away_elo, 2),
            "home_goal_diff": round(max(0, home_ag) - max(0, away_ag), 4),
            "avg_total_goals": round((max(0, home_ag) + max(0, away_ag)) / 2, 4),
            "label": label
        })

    return pd.DataFrame(records)


# ==================== 训练 ====================

def train_xgboost(df: pd.DataFrame):
    """训练 XGBoost 模型"""
    print(f"\n[Train] Dataset size: {len(df)}")

    # 特征和标签
    X = df[FEATURE_NAMES].values
    y = df["label"].values

    # 划分
    X_train, X_test, y_train, y_test = train_test_split(
        X, y, test_size=0.2, random_state=42, stratify=y
    )

    print(f"[Train] Train: {len(X_train)}, Test: {len(X_test)}")

    # 标准化
    scaler = StandardScaler()
    X_train_scaled = scaler.fit_transform(X_train)
    X_test_scaled = scaler.transform(X_test)

    # XGBoost 参数
    model = xgb.XGBClassifier(
        n_estimators=200,
        max_depth=5,
        learning_rate=0.05,
        subsample=0.8,
        colsample_bytree=0.8,
        min_child_weight=3,
        gamma=0.1,
        objective="multi:softprob",
        num_class=3,
        eval_metric="mlogloss",
        random_state=42,
        use_label_encoder=False,
        n_jobs=-1
    )

    print("[Train] Training XGBoost...")
    model.fit(X_train_scaled, y_train)

    # 评估
    y_pred = model.predict(X_test_scaled)
    y_pred_proba = model.predict_proba(X_test_scaled)

    acc = accuracy_score(y_test, y_pred)
    prec = precision_score(y_test, y_pred, average="weighted")
    rec = recall_score(y_test, y_pred, average="weighted")
    f1 = f1_score(y_test, y_pred, average="weighted")

    print(f"\n========== Model Evaluation ==========")
    print(f"Accuracy  : {acc:.4f}")
    print(f"Precision : {prec:.4f}")
    print(f"Recall    : {rec:.4f}")
    print(f"F1 Score  : {f1:.4f}")
    print(f"\nClassification Report:")
    print(classification_report(y_test, y_pred, target_names=["HOME_WIN", "DRAW", "AWAY_WIN"]))

    print(f"\nConfusion Matrix:")
    print(confusion_matrix(y_test, y_pred))

    # 交叉验证
    cv_scores = cross_val_score(model, X_train_scaled, y_train, cv=5, scoring="accuracy")
    print(f"\n5-Fold CV Accuracy: {cv_scores.mean():.4f} (+/- {cv_scores.std() * 2:.4f})")

    # 特征重要性
    importance = model.feature_importances_
    importance_df = pd.DataFrame({
        "feature": FEATURE_NAMES,
        "importance": importance
    }).sort_values("importance", ascending=False)
    print(f"\nTop 10 Feature Importance:")
    print(importance_df.head(10).to_string(index=False))

    # 保存模型
    model_path = os.path.join(MODEL_DIR, "xgboost_model.json")
    scaler_path = os.path.join(MODEL_DIR, "feature_scaler.joblib")
    fnames_path = os.path.join(MODEL_DIR, "feature_names.txt")
    results_path = os.path.join(MODEL_DIR, "train_results.json")

    joblib.dump(model, model_path)
    joblib.dump(scaler, scaler_path)

    with open(fnames_path, "w") as f:
        f.write("\n".join(FEATURE_NAMES))

    # 保存训练报告
    train_results = {
        "trained_at": datetime.now().isoformat(),
        "dataset_size": len(df),
        "accuracy": round(acc, 4),
        "precision": round(prec, 4),
        "recall": round(rec, 4),
        "f1": round(f1, 4),
        "cv_accuracy_mean": round(cv_scores.mean(), 4),
        "cv_accuracy_std": round(cv_scores.std(), 4),
        "feature_importance": [
            {"feature": row["feature"], "importance": round(row["importance"], 4)}
            for _, row in importance_df.iterrows()
        ],
        "classes": ["HOME_WIN", "DRAW", "AWAY_WIN"]
    }
    with open(results_path, "w", encoding="utf-8") as f:
        json.dump(train_results, f, ensure_ascii=False, indent=2)

    print(f"\n[OK] Model saved to {model_path}")
    print(f"[OK] Scaler saved to {scaler_path}")
    print(f"[OK] Results saved to {results_path}")
    print(f"[OK] Feature names saved to {fnames_path}")

    return model, scaler, train_results


# ==================== 主流程 ====================

def main():
    print("=" * 50)
    print("Football Match Prediction - XGBoost Training")
    print("=" * 50)

    print(f"[Config] Data source: {TRAIN_DATA_SOURCE}")
    print(f"[Config] Seasons: {', '.join(str(season) for season in TRAIN_SEASONS)}")
    print(f"[Config] Max matches per season: {MAX_MATCHES_PER_SEASON}")
    print(f"[Config] Min real records: {MIN_REAL_RECORDS}")
    print(f"[Config] Allow synthetic data: {ALLOW_SYNTHETIC_DATA}")

    all_records = []

    if TRAIN_DATA_SOURCE == "football-data":
        if not FOOTBALL_DATA_API_KEY:
            raise RuntimeError("Missing FOOTBALL_DATA_API_KEY. Please set it before training.")
        print(f"[Config] football-data competitions: {', '.join(FOOTBALL_DATA_CODES)}")
        for code in FOOTBALL_DATA_CODES:
            name = FOOTBALL_DATA_COMPETITIONS.get(code, code)
            for season in TRAIN_SEASONS:
                print(f"\n[Fetch] football-data {name} {season}...")
                matches = fetch_football_data_matches(code, season)
                print(f"[Fetch] Got {len(matches)} matches")
                records = build_football_data_features(matches, MAX_MATCHES_PER_SEASON)
                all_records.extend(records)
                print(f"[Fetch] Processed {len(records)} matches from {name} {season}")
    elif TRAIN_DATA_SOURCE == "api-football":
        if not API_KEY:
            raise RuntimeError("Missing API_FOOTBALL_API_KEY. Please set it before training.")

        leagues = [
            {
                "id": league_id,
                "name": DEFAULT_LEAGUES.get(league_id, f"League {league_id}"),
                "seasons": TRAIN_SEASONS,
            }
            for league_id in TRAIN_LEAGUE_IDS
        ]

        print(f"[Config] API-Football leagues: {', '.join(str(item['id']) for item in leagues)}")

        for league in leagues:
            for season in league["seasons"]:
                print(f"\n[Fetch] {league['name']} {season}...")
                fixtures = fetch_fixtures(league["id"], season, total_pages=10)
                print(f"[Fetch] Got {len(fixtures)} fixtures")

                processed = 0
                for fixture in fixtures:
                    if processed >= MAX_MATCHES_PER_SEASON:
                        break
                    teams = fixture.get("teams", {})
                    home_id = teams.get("home", {}).get("id")
                    away_id = teams.get("away", {}).get("id")
                    if not home_id or not away_id:
                        continue

                    home_stats = fetch_team_stats(home_id, league["id"], season)
                    away_stats = fetch_team_stats(away_id, league["id"], season)

                    feat = extract_fixture_features(fixture, home_stats, away_stats)
                    if feat:
                        all_records.append(feat)
                        processed += 1

                print(f"[Fetch] Processed {processed} matches from {league['name']} {season}")
    else:
        raise RuntimeError("TRAIN_DATA_SOURCE must be either 'api-football' or 'football-data'.")

    if len(all_records) < MIN_REAL_RECORDS:
        if ALLOW_SYNTHETIC_DATA:
            print(f"\n[Warn] Only {len(all_records)} real records from API, adding synthetic data")
            synthetic = generate_synthetic_data(n_samples=SYNTHETIC_SAMPLES)
            all_records.extend(synthetic.to_dict("records"))
        else:
            raise RuntimeError(
                f"Only {len(all_records)} real records fetched, below MIN_REAL_RECORDS={MIN_REAL_RECORDS}. "
                "Set ALLOW_SYNTHETIC_DATA=true only if you want demo training."
            )

    df = pd.DataFrame(all_records)
    print(f"\n[Data] Total records: {len(df)}")
    print(f"[Data] Class distribution:\n{df['label'].value_counts()}")

    train_xgboost(df)


if __name__ == "__main__":
    main()
