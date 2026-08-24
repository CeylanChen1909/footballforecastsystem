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
import shutil
import joblib
import hashlib
import time
import math
import warnings
warnings.filterwarnings("ignore")
import re

try:
    import pymysql
except ImportError:  # optional in lightweight inference containers
    pymysql = None


def load_local_env() -> None:
    """训练脚本独立运行时也读取项目根目录 .env（不覆盖调用方显式传入的变量）。"""
    env_path = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".env"))
    if not os.path.exists(env_path):
        return
    try:
        with open(env_path, "r", encoding="utf-8-sig") as env_file:
            for raw_line in env_file:
                line = raw_line.strip()
                if not line or line.startswith("#") or "=" not in line:
                    continue
                key, value = line.split("=", 1)
                key = key.strip()
                value = value.strip().strip("\"'")
                if key and key not in os.environ:
                    os.environ[key] = value
    except OSError as exc:
        print(f"[WARN] Unable to read local .env: {exc}")


load_local_env()

import numpy as np
import pandas as pd
import requests
from datetime import datetime, timedelta
from sklearn.preprocessing import StandardScaler
from sklearn.linear_model import LogisticRegression
from sklearn.metrics import (
    accuracy_score, precision_score, recall_score,
    f1_score, classification_report, confusion_matrix, log_loss,
    balanced_accuracy_score
)
import xgboost as xgb

try:
    from catboost import CatBoostClassifier
except ImportError:  # optional candidate; XGBoost/ELO remain the safe fallback
    CatBoostClassifier = None

# ==================== 配置 ====================
TRAIN_DATA_SOURCE = os.environ.get("TRAIN_DATA_SOURCE", "football-data").strip().lower()
# API-Football 的 team statistics 接口返回赛季累计值，无法证明它们在
# fixture 开赛前已经可见。默认禁止用这类聚合值训练，避免时间泄漏；
# 只有完成 point-in-time 快照改造后，才允许显式开启此开关。
ALLOW_NON_POINT_IN_TIME_API_TRAINING = os.environ.get(
    "ALLOW_NON_POINT_IN_TIME_API_TRAINING", "false"
).strip().lower() in {"1", "true", "yes", "on"}
API_KEY = os.environ.get("API_FOOTBALL_API_KEY", os.environ.get("API_FOOTBALL_KEY", "")).strip()
BASE_URL = "https://v3.football.api-sports.io"
HEADERS = {"x-apisports-key": API_KEY}

FOOTBALL_DATA_API_KEY = os.environ.get(
    "FOOTBALL_DATA_API_KEY", os.environ.get("FOOTBALL_DATA_TOKEN", "")
).strip()
FOOTBALL_DATA_BASE_URL = "https://api.football-data.org/v4"
FOOTBALL_DATA_HEADERS = {"X-Auth-Token": FOOTBALL_DATA_API_KEY}

DEFAULT_LEAGUES = {
    39: "Premier League",
    140: "La Liga",
    135: "Serie A",
    78: "Bundesliga",
    61: "Ligue 1",
    88: "Eredivisie",
    94: "Primeira Liga",
    40: "Championship",
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


# 默认覆盖主要联赛；显式设置 TRAIN_LEAGUES 时可缩小范围。
TRAIN_LEAGUE_IDS = parse_int_list(os.environ.get("TRAIN_LEAGUES"), [39, 140, 135, 78, 61, 88, 94, 40])
FOOTBALL_DATA_CODES = parse_str_list(
    os.environ.get("FOOTBALL_DATA_COMPETITIONS"),
    ["PL", "BL1", "PD", "FL1", "SA", "DED", "PPL", "ELC"]
)
# 优先使用六个赛季，尽可能提高球队样本与历史 xG 覆盖；没有权限的旧赛季
# 会被 football-data 返回为空并跳过，仍可通过 TRAIN_SEASONS 显式缩小窗口。
TRAIN_SEASONS = parse_int_list(os.environ.get("TRAIN_SEASONS"), [2020, 2021, 2022, 2023, 2024, 2025])
MAX_MATCHES_PER_SEASON = int(os.environ.get("MAX_MATCHES_PER_SEASON", 380))
MIN_REAL_RECORDS = int(os.environ.get("MIN_REAL_RECORDS", 500))
ALLOW_SYNTHETIC_DATA = os.environ.get("ALLOW_SYNTHETIC_DATA", "false").lower() in {"1", "true", "yes", "y"}
SYNTHETIC_SAMPLES = int(os.environ.get("SYNTHETIC_SAMPLES", 3000))
TRAIN_CACHE_DIR = os.environ.get("TRAIN_CACHE_DIR", os.path.join(os.path.dirname(__file__), "data_cache"))
TRAIN_CACHE_TTL_HOURS = float(os.environ.get("TRAIN_CACHE_TTL_HOURS", 168))
REQUEST_SLEEP_SECONDS = float(os.environ.get("TRAIN_REQUEST_SLEEP_SECONDS", 0))
# Understat supplies historical, match-level xG/xGA snapshots.  It is an
# enrichment provider only; fixture identity and results still come from
# football-data/BBC.  Responses are cached so repeated training is cheap.
UNDERSTAT_ENABLED = os.environ.get("UNDERSTAT_ENRICHMENT_ENABLED", "true").strip().lower() in {"1", "true", "yes", "on"}
UNDERSTAT_CACHE_TTL_HOURS = float(os.environ.get("UNDERSTAT_CACHE_TTL_HOURS", 720))
UNDERSTAT_REQUEST_SLEEP_SECONDS = float(os.environ.get("UNDERSTAT_REQUEST_SLEEP_SECONDS", 0.15))
UNDERSTAT_TIMEOUT_SECONDS = float(os.environ.get("UNDERSTAT_TIMEOUT_SECONDS", 30))
UNDERSTAT_MATCH_DETAIL_TIMEOUT_SECONDS = float(os.environ.get("UNDERSTAT_MATCH_DETAIL_TIMEOUT_SECONDS", 8))
UNDERSTAT_MATCH_DETAIL_LIMIT = int(os.environ.get("UNDERSTAT_MATCH_DETAIL_LIMIT", 4))
UNDERSTAT_LEAGUES = {
    "PL": "EPL",
    "PD": "La_liga",
    "BL1": "Bundesliga",
    "SA": "Serie_A",
    "FL1": "Ligue_1",
}
# Five leagues with reliable historical Understat coverage use specialist
# models. The remaining configured competitions continue to use the global
# model, so they remain predictable without pretending to have specialist
# data.
SPECIALIST_COMPETITION_CODES = ("PL", "BL1", "PD", "FL1", "SA")
# 平局是少数类，融合配方选择时提高其损失权重，避免只优化总体 log loss 而永远不输出平局。
DRAW_LOSS_WEIGHT = float(os.environ.get("DRAW_LOSS_WEIGHT", 1.7))
# Tuned only on prior seasons with walk-forward validation. 35 points was the
# historical placeholder; 65 is the robust value across the 2024/2025/2026
# windows and is kept in one place so training and inference remain aligned.
ELO_K_FACTOR = float(os.environ.get("ELO_K_FACTOR", 28))
ELO_HOME_ADVANTAGE = float(os.environ.get("ELO_HOME_ADVANTAGE", 65))
ENABLE_CATBOOST_CANDIDATE = os.environ.get("ENABLE_CATBOOST_CANDIDATE", "true").strip().lower() in {"1", "true", "yes", "on"}

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

BASE_FEATURE_NAMES = [
    "home_elo", "away_elo", "elo_diff",
    "home_win_rate", "away_win_rate",
    "home_avg_goals", "away_avg_goals",
    "home_avg_loss", "away_avg_loss",
    "home_avg_cards", "away_avg_cards",
    "home_days_rest", "away_days_rest",
    "h2h_home_wins", "h2h_draws", "h2h_away_wins",
    "home_win_rate_diff", "elo_sum",
    "home_goal_diff", "avg_total_goals",
    # 赛前滚动特征（严格只使用 kickoff 之前已经结束的比赛）
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
# Provider-specific prematch fields are now part of the explicit contract
# above. Keep this legacy switch for old bundles, but do not append duplicate
# xG/shots columns when training a new model.
OPTIONAL_PREMATCH_FEATURES = []
USE_OPTIONAL_PREMATCH_FEATURES = os.environ.get("USE_OPTIONAL_PREMATCH_FEATURES", "false").lower() in {"1", "true", "yes", "y"}
FEATURE_NAMES = BASE_FEATURE_NAMES + (OPTIONAL_PREMATCH_FEATURES if USE_OPTIONAL_PREMATCH_FEATURES else [])

# Filled from the business database when available.  Training remains
# reproducible from football-data cache alone, while these maps make the
# feature contract consume the same provider snapshots used by live inference.
DETAIL_STATS_BY_TEAMS: dict[tuple[str, str], list[tuple[datetime, dict]]] = {}
PREMATCH_ENRICHMENT_BY_TEAMS: dict[tuple[str, str], list[tuple[datetime, dict]]] = {}
# Understat's own match ids do not always join to football-data/BBC fixture
# ids. Keep a second, team/date keyed index so a missing pair join can still
# contribute historical xG. Every lookup below is strictly before kickoff.
UNDERSTAT_TEAM_XG_BY_NAME: dict[str, list[tuple[datetime, float, float]]] = {}


def _understat_cache_path(league: str, season: int, kind: str = "league") -> str:
    safe_league = str(league).replace(" ", "_").replace("/", "_")
    return os.path.join(TRAIN_CACHE_DIR, f"understat-{safe_league}-{season}-{kind}.json")


def _read_json_cache(path: str, ttl_hours: float) -> object | None:
    if not os.path.exists(path):
        return None
    try:
        age_hours = (datetime.now().timestamp() - os.path.getmtime(path)) / 3600
        if ttl_hours >= 0 and age_hours > ttl_hours:
            return None
        with open(path, "r", encoding="utf-8") as cache_file:
            return json.load(cache_file)
    except (OSError, ValueError, TypeError) as exc:
        print(f"[WARN] enrichment cache read failed ({path}): {exc}")
        return None


def _write_json_cache(path: str, payload: object) -> None:
    os.makedirs(TRAIN_CACHE_DIR, exist_ok=True)
    temp_path = f"{path}.tmp"
    try:
        with open(temp_path, "w", encoding="utf-8") as cache_file:
            json.dump(payload, cache_file, ensure_ascii=False)
        os.replace(temp_path, path)
    except OSError as exc:
        print(f"[WARN] enrichment cache write failed ({path}): {exc}")
        try:
            if os.path.exists(temp_path):
                os.remove(temp_path)
        except OSError:
            pass


def _fetch_understat_json(url: str, referer: str, timeout: float | None = None) -> object | None:
    headers = {
        "User-Agent": "Mozilla/5.0 (compatible; FootballForecast/1.0)",
        "Accept": "application/json",
        "Referer": referer,
        "X-Requested-With": "XMLHttpRequest",
    }
    try:
        response = requests.get(url, headers=headers, timeout=timeout or UNDERSTAT_TIMEOUT_SECONDS)
        response.raise_for_status()
        return response.json()
    except Exception as exc:
        print(f"[WARN] Understat request failed: {url}: {exc}")
        return None


def _as_float(value: object, default: float = 0.0) -> float:
    try:
        parsed = float(value)
        return parsed if math.isfinite(parsed) else default
    except (TypeError, ValueError):
        return default


def _append_detail_snapshot(home: str, away: str, match_dt: datetime, home_stat: dict, away_stat: dict) -> None:
    if not home or not away or match_dt is None:
        return
    pair = (normalize_team_name(home), normalize_team_name(away))
    DETAIL_STATS_BY_TEAMS.setdefault(pair, []).append((match_dt, {
        "home_stat": home_stat,
        "away_stat": away_stat,
        "source": "understat",
    }))


def _append_understat_team_snapshot(team: object, match_dt: datetime, xg: object, xga: object) -> None:
    """Index a completed Understat observation by normalized team name."""
    name = normalize_team_name(team)
    when = match_dt
    xg_value = _as_float(xg)
    xga_value = _as_float(xga)
    if not name or when is None or xg_value <= 0 or xga_value <= 0:
        return
    rows = UNDERSTAT_TEAM_XG_BY_NAME.setdefault(name, [])
    # The same snapshot may be loaded from the DB cache and local JSON cache.
    # De-duplicate by timestamp and values so it cannot overweight a team.
    if any(existing_when == when and abs(existing_xg - xg_value) < 1e-9
           and abs(existing_xga - xga_value) < 1e-9
           for existing_when, existing_xg, existing_xga in rows):
        return
    rows.append((when, xg_value, xga_value))


def _understat_team_history(team: object, match_dt: datetime, limit: int = 5) -> tuple[list[float], list[float]]:
    """Return the latest strictly pre-kickoff xG/xGA observations for a team."""
    name = normalize_team_name(team)
    if not name or match_dt is None:
        return [], []
    prior = [row for row in UNDERSTAT_TEAM_XG_BY_NAME.get(name, []) if row[0] < match_dt]
    prior.sort(key=lambda row: row[0], reverse=True)
    selected = prior[:max(1, limit)]
    return [row[1] for row in selected if row[1] > 0], [row[2] for row in selected if row[2] > 0]


def _load_understat_match_details(league: str, season: int, dates: list[dict]) -> dict[str, dict]:
    """Optionally cache a bounded number of match payloads for shots.

    League data already contains complete xG/xGA.  Match details are opt-in
    and bounded because they are only needed for shot totals; a missing detail
    must never prevent training.
    """
    if UNDERSTAT_MATCH_DETAIL_LIMIT <= 0:
        return {}
    path = _understat_cache_path(league, season, "matches")
    cached = _read_json_cache(path, UNDERSTAT_CACHE_TTL_HOURS)
    details = cached if isinstance(cached, dict) else {}
    fetched = 0
    for item in dates:
        match_id = str(item.get("id") or "")
        if not match_id or match_id in details:
            continue
        if len(details) >= UNDERSTAT_MATCH_DETAIL_LIMIT:
            break
        payload = _fetch_understat_json(
            f"https://understat.com/getMatchData/{match_id}",
            f"https://understat.com/match/{match_id}",
            UNDERSTAT_MATCH_DETAIL_TIMEOUT_SECONDS,
        )
        if isinstance(payload, dict):
            details[match_id] = payload
            fetched += 1
            if UNDERSTAT_REQUEST_SLEEP_SECONDS > 0:
                time.sleep(UNDERSTAT_REQUEST_SLEEP_SECONDS)
    if fetched > 0 or not os.path.exists(path):
        _write_json_cache(path, details)
    return details


def load_understat_enrichment() -> None:
    """Load point-in-time-safe xG/xGA (and optional shots) snapshots.

    ``dates`` contains one record per completed fixture with both teams,
    kickoff time and xG.  The rolling feature builder appends the target's
    snapshot only after constructing that target row, so the target result and
    target post-match statistics cannot leak into its features.
    """
    if not UNDERSTAT_ENABLED:
        print("[Enrichment] Understat disabled by UNDERSTAT_ENRICHMENT_ENABLED")
        return
    loaded = 0
    xg_snapshots = 0
    shot_snapshots = 0
    for code in FOOTBALL_DATA_CODES:
        league = UNDERSTAT_LEAGUES.get(code)
        if not league:
            continue
        for season in TRAIN_SEASONS:
            path = _understat_cache_path(league, season)
            payload = _read_json_cache(path, UNDERSTAT_CACHE_TTL_HOURS)
            if not isinstance(payload, dict):
                payload = _fetch_understat_json(
                    f"https://understat.com/getLeagueData/{league}/{season}",
                    f"https://understat.com/league/{league}/{season}",
                )
                if isinstance(payload, dict):
                    _write_json_cache(path, payload)
                    if UNDERSTAT_REQUEST_SLEEP_SECONDS > 0:
                        time.sleep(UNDERSTAT_REQUEST_SLEEP_SECONDS)
            if not isinstance(payload, dict):
                continue
            dates = payload.get("dates") if isinstance(payload.get("dates"), list) else []
            details = _load_understat_match_details(league, season, dates)
            for item in dates:
                if not isinstance(item, dict) or not item.get("isResult", True):
                    continue
                home = item.get("h", {}).get("title") if isinstance(item.get("h"), dict) else ""
                away = item.get("a", {}).get("title") if isinstance(item.get("a"), dict) else ""
                match_dt = parse_provider_time(item.get("datetime"))
                if not home or not away or match_dt is None:
                    continue
                xg = item.get("xG") if isinstance(item.get("xG"), dict) else {}
                home_xg = _as_float(xg.get("h"))
                away_xg = _as_float(xg.get("a"))
                home_stat = {"xg": home_xg, "xga": away_xg}
                away_stat = {"xg": away_xg, "xga": home_xg}
                match_id = str(item.get("id") or "")
                detail = details.get(match_id, {}) if isinstance(details, dict) else {}
                rosters = detail.get("rosters") if isinstance(detail, dict) else {}
                if isinstance(rosters, dict):
                    for side, stat in (("h", home_stat), ("a", away_stat)):
                        players = rosters.get(side)
                        if not isinstance(players, dict):
                            continue
                        shots = sum(_as_float(player.get("shots")) for player in players.values() if isinstance(player, dict))
                        on_target = sum(_as_float(player.get("shots_on_target")) for player in players.values() if isinstance(player, dict))
                        starters = sum(
                            1 for player in players.values()
                            if isinstance(player, dict)
                            and str(player.get("roster_in", "0")) == "0"
                            and _as_float(player.get("time")) > 0
                        )
                        if shots > 0:
                            stat["shots"] = shots
                        if on_target > 0:
                            stat["on_target"] = on_target
                        if starters > 0:
                            stat["lineup_stability"] = min(1.0, starters / 11.0)
                _append_understat_team_snapshot(home, match_dt, home_xg, away_xg)
                _append_understat_team_snapshot(away, match_dt, away_xg, home_xg)
                _append_detail_snapshot(home, away, match_dt, home_stat, away_stat)
                loaded += 1
                xg_snapshots += int(home_xg > 0 or away_xg > 0)
                shot_snapshots += int("shots" in home_stat or "shots" in away_stat)
    print(f"[Enrichment] Understat snapshots loaded: fixtures={loaded}, xg={xg_snapshots}, shots={shot_snapshots}")

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
    os.makedirs(TRAIN_CACHE_DIR, exist_ok=True)
    cache_key = hashlib.sha1(f"{competition_code}:{season}".encode("utf-8")).hexdigest()[:16]
    cache_path = os.path.join(TRAIN_CACHE_DIR, f"football-data-{competition_code}-{season}-{cache_key}.json")
    if os.path.exists(cache_path):
        age_hours = (datetime.now().timestamp() - os.path.getmtime(cache_path)) / 3600
        if age_hours <= TRAIN_CACHE_TTL_HOURS:
            try:
                with open(cache_path, "r", encoding="utf-8") as f:
                    cached = json.load(f)
                if isinstance(cached, list):
                    print(f"[Cache] football-data {competition_code} {season}: {len(cached)} matches")
                    return cached
            except Exception as e:
                print(f"[WARN] Cache read failed for {competition_code} {season}: {e}")

    params = {"season": season}
    url = f"{FOOTBALL_DATA_BASE_URL}/competitions/{competition_code}/matches"
    for attempt in range(1, 4):
        try:
            resp = requests.get(url, headers=FOOTBALL_DATA_HEADERS, params=params, timeout=20)
            data = resp.json()
            if resp.status_code == 429:
                wait = min(60, 5 * attempt)
                print(f"[WARN] football-data rate limited, retrying in {wait}s")
                time.sleep(wait)
                continue
            if resp.status_code >= 400:
                print(f"[API ERROR] football-data {competition_code} {season}: {data}")
                return []
            matches = data.get("matches", [])
            with open(cache_path, "w", encoding="utf-8") as f:
                json.dump(matches, f, ensure_ascii=False)
            if REQUEST_SLEEP_SECONDS > 0:
                time.sleep(REQUEST_SLEEP_SECONDS)
            return matches
        except Exception as e:
            print(f"[WARN] football-data fetch attempt {attempt} failed for {competition_code} {season}: {e}")
            if attempt < 3:
                time.sleep(min(10, attempt * 2))
    return []


def nested_numeric(value, keys: tuple[str, ...]) -> float:
    """Best-effort extraction from provider statistics; missing values stay 0 and are audited."""
    if isinstance(value, dict):
        for key in keys:
            candidate = value.get(key)
            if isinstance(candidate, (int, float)):
                return float(candidate)
        for child in value.values():
            found = nested_numeric(child, keys)
            if found:
                return found
    elif isinstance(value, list):
        for child in value:
            found = nested_numeric(child, keys)
            if found:
                return found
    return 0.0


def normalize_team_name(value: object) -> str:
    """Match provider names without letting IDs or accents split history."""
    import re
    import unicodedata
    text = unicodedata.normalize("NFKD", str(value or "")).encode("ascii", "ignore").decode().lower()
    compact = re.sub(r"[^a-z0-9]+", "", text)
    # football-data frequently appends FC/AFC/CF while Understat uses the
    # shorter display name.  Strip these boundary-only club suffixes before
    # applying the explicit aliases below.
    compact = re.sub(r"^(?:afc|fc|cf)(?=[a-z])", "", compact)
    compact = re.sub(r"(?:afc|fc|cf)$", "", compact)
    aliases = {
        "deportivoalaves": "alaves", "sbvexcelsior": "excelsior",
        "racingclubdelens": "lens", "rclens": "lens",
        "angerssco": "angers", "lilleosc": "lille", "celtavigo": "celta",
        "acmonza": "monza", "internazionalemilano": "inter", "intermilan": "inter",
        "como1907": "como", "udinesecalcio": "udinese", "cdnacional": "nacional",
        "vitoria": "vitoriaguimaraes", "vitoriaguimaraes": "vitoriaguimaraes",
        "sportingcp": "sportingportugal", "sportinglisbon": "sportingportugal",
        "olympiquelyonnais": "lyon", "atleti": "atleticomadrid", "atleticomadrid": "atleticomadrid",
        "brightonhove": "brightonhovealbion", "brightonhovealbion": "brightonhovealbion",
        "estorilpraia": "estoril", "sittard": "fortunasittard", "fortunasittard": "fortunasittard",
        "goahead": "goaheadeagles", "goaheadeagles": "goaheadeagles",
        "zwolle": "peczwolle", "peczwolle": "peczwolle",
    }
    return aliases.get(compact, compact)


def parse_provider_time(value: object) -> datetime | None:
    if isinstance(value, datetime):
        return value.replace(tzinfo=None)
    if value is None:
        return None
    text = str(value).replace("Z", "+00:00")
    try:
        parsed = datetime.fromisoformat(text)
        return parsed.replace(tzinfo=None)
    except ValueError:
        return None


def _payload_rows(payload: object) -> list[dict]:
    if isinstance(payload, list):
        return [item for item in payload if isinstance(item, dict)]
    if isinstance(payload, dict):
        response = payload.get("response")
        if isinstance(response, list):
            return [item for item in response if isinstance(item, dict)]
    return []


def _odds_probabilities(payload: object) -> tuple[float, float, float]:
    rows = _payload_rows(payload)
    values = []
    for row in rows:
        bookmakers = row.get("bookmakers") if isinstance(row.get("bookmakers"), list) else ([row] if row.get("bets") else [])
        for bookmaker in bookmakers:
            for bet in bookmaker.get("bets", []) if isinstance(bookmaker, dict) else []:
                for value in bet.get("values", []) if isinstance(bet, dict) else []:
                    label = str(value.get("value", "")).lower()
                    try:
                        odd = float(value.get("odd", 0))
                    except (TypeError, ValueError):
                        odd = 0
                    if odd <= 1:
                        continue
                    if label in {"1", "home"} or "home" in label:
                        values.append((0, 1 / odd))
                    elif label in {"x", "draw"} or "draw" in label:
                        values.append((1, 1 / odd))
                    elif label in {"2", "away"} or "away" in label:
                        values.append((2, 1 / odd))
                if len({index for index, _ in values}) == 3:
                    break
            if len({index for index, _ in values}) == 3:
                break
        if len({index for index, _ in values}) == 3:
            break
    implied = [next((value for index, value in values if index == target), 0.0) for target in range(3)]
    total = sum(implied)
    return tuple(value / total for value in implied) if total > 0 else (0.0, 0.0, 0.0)


def load_database_enrichment() -> None:
    """Load cached provider details for both training and live inference.

    This is deliberately optional: an unavailable local MySQL instance must
    never make a reproducible football-data training run fail.
    """
    if pymysql is None or os.environ.get("TRAIN_DB_ENRICHMENT", "true").lower() not in {"1", "true", "yes", "y"}:
        return
    try:
        connection = pymysql.connect(**MYSQL_CONFIG, cursorclass=pymysql.cursors.DictCursor, connect_timeout=5)
        with connection.cursor() as cursor:
            cursor.execute("""
                SELECT m.fixture_id,m.match_time,m.home_team_id,m.away_team_id,
                       m.home_team_name,m.away_team_name,d.detail_type,d.payload_json,
                       d.status,d.fetched_at
                FROM crawler_matches m
                JOIN t_match_detail_snapshot d ON d.fixture_id=m.fixture_id
                WHERE m.fixture_id IS NOT NULL AND m.fixture_id > 0
                  AND m.match_time >= DATE_SUB(NOW(), INTERVAL 3 YEAR)
                ORDER BY d.fetched_at DESC
                """)
            rows = cursor.fetchall()
            try:
                cursor.execute("""
                    SELECT team_name,match_time,xg,xga
                    FROM t_understat_team_xg_cache
                    WHERE match_time >= DATE_SUB(NOW(), INTERVAL 3 YEAR)
                    ORDER BY match_time DESC
                    LIMIT 50000
                    """)
                understat_rows = cursor.fetchall()
            except Exception as exc:
                # Older local schemas may not have the optional Understat
                # cache yet; detail enrichment should still remain usable.
                print(f"[WARN] Understat team cache unavailable: {exc}")
                understat_rows = []
        connection.close()
        for row in understat_rows:
            when = parse_provider_time(row.get("match_time"))
            if when is None:
                continue
            _append_understat_team_snapshot(
                row.get("team_name"), when, row.get("xg"), row.get("xga")
            )
        grouped: dict[tuple[str, str], dict] = {}
        for row in rows:
            fixture = str(row.get("fixture_id"))
            detail_type = str(row.get("detail_type", ""))
            key = (fixture, detail_type)
            if key not in grouped:
                grouped[key] = row
        by_fixture: dict[str, dict] = {}
        for (fixture, detail_type), row in grouped.items():
            match_dt = parse_provider_time(row.get("match_time"))
            if match_dt is None:
                continue
            payload = json.loads(row.get("payload_json") or "[]")
            bundle = by_fixture.setdefault(fixture, {
                "date": match_dt, "home": normalize_team_name(row.get("home_team_name")),
                "away": normalize_team_name(row.get("away_team_name")),
                "home_id": str(row.get("home_team_id") or ""), "away_id": str(row.get("away_team_id") or ""),
                "stats": {}, "prematch": {}, "match_time": match_dt,
            })
            if detail_type in {"statistics", "xg"} and str(row.get("status")) == "NORMAL":
                for team in _payload_rows(payload):
                    team_id = str((team.get("team") or {}).get("id") or "")
                    if not team_id:
                        continue
                    xg = shots = on_target = 0.0
                    for item in team.get("statistics", []) if isinstance(team.get("statistics"), list) else []:
                        typ = str(item.get("type", "")).lower()
                        value = item.get("value")
                        try:
                            numeric = float(str(value).replace("%", "")) if value is not None else 0.0
                        except ValueError:
                            numeric = 0.0
                        if "expected" in typ or typ == "xg": xg = numeric
                        if typ in {"total shots", "shots"}: shots = numeric
                        if "shots on goal" in typ or "shots on target" in typ: on_target = numeric
                    bundle["stats"][team_id] = {"xg": xg, "shots": shots, "on_target": on_target}
            elif detail_type in {"odds", "lineups", "injuries"}:
                fetched = parse_provider_time(row.get("fetched_at"))
                if fetched is None or fetched >= match_dt or str(row.get("status")) != "NORMAL":
                    continue
                if detail_type == "odds":
                    home_prob, draw_prob, away_prob = _odds_probabilities(payload)
                    bundle["prematch"].update({"market_home_prob": home_prob, "market_draw_prob": draw_prob, "market_away_prob": away_prob})
                elif detail_type == "lineups":
                    for team in _payload_rows(payload):
                        team_id = str((team.get("team") or {}).get("id") or "")
                        stability = min(1.0, len(team.get("startXI", [])) / 11.0) if isinstance(team.get("startXI"), list) else 0.0
                        if team_id == bundle["home_id"]: bundle["prematch"]["home_lineup_stability"] = stability
                        elif team_id == bundle["away_id"]: bundle["prematch"]["away_lineup_stability"] = stability
                else:
                    home_impact = away_impact = 0.0
                    for item in _payload_rows(payload):
                        team_id = str((item.get("team") or {}).get("id") or "")
                        player = item.get("player") or {}
                        impact = 1.0 if "susp" in str(player.get("type", "")).lower() else 0.5
                        if team_id == bundle["home_id"]: home_impact += impact
                        elif team_id == bundle["away_id"]: away_impact += impact
                    bundle["prematch"]["home_injury_impact"] = min(1.0, home_impact / 5.0)
                    bundle["prematch"]["away_injury_impact"] = min(1.0, away_impact / 5.0)
        for bundle in by_fixture.values():
            stats = bundle["stats"]
            if len(stats) >= 2:
                home_stat = stats.get(bundle["home_id"], {})
                away_stat = stats.get(bundle["away_id"], {})
                bundle["home_stat"] = home_stat
                bundle["away_stat"] = away_stat
                bundle["home_stat"]["xga"] = away_stat.get("xg", 0.0)
                bundle["away_stat"]["xga"] = home_stat.get("xg", 0.0)
            pair = (bundle["home"], bundle["away"])
            DETAIL_STATS_BY_TEAMS.setdefault(pair, []).append((bundle["date"], bundle))
            PREMATCH_ENRICHMENT_BY_TEAMS.setdefault(pair, []).append((bundle["date"], bundle.get("prematch", {})))
        print(f"[Enrichment] loaded detail snapshots: fixtures={len(by_fixture)}, stats={sum(bool(v.get('stats')) for v in by_fixture.values())}, understat_team_rows={len(understat_rows)}")
    except Exception as exc:
        print(f"[WARN] database detail enrichment unavailable: {exc}")


def lookup_enrichment(match: dict) -> tuple[dict, dict]:
    match_dt = parse_provider_time(match.get("utcDate"))
    if match_dt is None:
        return {}, {}
    home = normalize_team_name((match.get("homeTeam") or {}).get("name"))
    away = normalize_team_name((match.get("awayTeam") or {}).get("name"))
    candidates = DETAIL_STATS_BY_TEAMS.get((home, away), [])
    reversed_candidates = DETAIL_STATS_BY_TEAMS.get((away, home), [])
    best = min(candidates, key=lambda item: abs((item[0] - match_dt).total_seconds()), default=None)
    reversed_best = min(reversed_candidates, key=lambda item: abs((item[0] - match_dt).total_seconds()), default=None)
    chosen = best if best and (not reversed_best or abs((best[0] - match_dt).total_seconds()) <= abs((reversed_best[0] - match_dt).total_seconds())) else reversed_best
    if chosen is None or abs((chosen[0] - match_dt).days) > 2:
        return {}, {}
    bundle = chosen[1]
    if chosen in reversed_candidates:
        return bundle.get("away_stat", {}), bundle.get("home_stat", {})
    return bundle.get("home_stat", {}), bundle.get("away_stat", {})


def lookup_prematch(match: dict) -> dict:
    match_dt = parse_provider_time(match.get("utcDate"))
    if match_dt is None:
        return {}
    pair = (normalize_team_name((match.get("homeTeam") or {}).get("name")), normalize_team_name((match.get("awayTeam") or {}).get("name")))
    candidates = PREMATCH_ENRICHMENT_BY_TEAMS.get(pair, [])
    if not candidates:
        return {}
    chosen = min(candidates, key=lambda item: abs((item[0] - match_dt).total_seconds()))
    return chosen[1] if abs((chosen[0] - match_dt).days) <= 2 else {}


def build_football_data_features(matches: list, max_matches: int | None = None,
                                 competition_code: str = "") -> list[dict]:
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
    # standings are maintained incrementally, so rank/points features are
    # snapshots as-of the fixture and never include the target result.
    standings: dict[int, dict[str, float]] = {}
    last_played: dict[int, datetime] = {}
    elo: dict[int, float] = {}
    h2h_stats: dict[tuple[int, int], list[int]] = {}
    records = []
    previous_global_dt: datetime | None = None

    def get_elo(team_id: int) -> float:
        if team_id not in elo:
            elo[team_id] = 1500.0
        return elo[team_id]

    def update_elo(home_id: int, away_id: int, label: int, k: float = ELO_K_FACTOR) -> None:
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

    def calc_team(team_id: int, match_dt: datetime, team_name: object = "") -> dict:
        all_history = team_stats.get(team_id, [])
        recent = all_history[-10:]
        if not recent:
            recent = []
        n = len(recent)
        wins = sum(1 for item in recent if item["result"] == 0)
        draws = sum(1 for item in recent if item["result"] == 1)
        goals = sum(item["goals"] for item in recent)
        conceded = sum(item["conceded"] for item in recent)
        cards = sum(item.get("cards", 1.5) for item in recent)
        xg_values = [item.get("xg") for item in recent if item.get("xg") is not None]
        xga_values = [item.get("xga") for item in recent if item.get("xga") is not None]
        # Pair-level joins are preferred because they carry the exact fixture
        # identity. If a provider name/time mismatch left holes, backfill only
        # the missing side from team snapshots strictly before this kickoff.
        # This preserves point-in-time safety while making xG useful for more
        # than the subset of fixtures that share provider ids.
        if len(xg_values) < 3 or len(xga_values) < 3:
            prior_xg, prior_xga = _understat_team_history(team_name, match_dt, 5)
            if prior_xg:
                xg_values = (xg_values + prior_xg)[:5]
            if prior_xga:
                xga_values = (xga_values + prior_xga)[:5]
        shots_values = [item.get("shots") for item in recent if item.get("shots") is not None]
        on_target_values = [item.get("on_target") for item in recent if item.get("on_target") is not None]
        lineup_values = [item.get("lineup_stability") for item in recent if item.get("lineup_stability") is not None]
        previous_dt = last_played.get(team_id)
        days_rest = 7 if previous_dt is None else max(1, min(30, (match_dt - previous_dt).days))
        form_5 = recent[-5:]
        home_history = [item for item in all_history if item.get("home")][-5:]
        away_history = [item for item in all_history if not item.get("home")][-5:]

        def form(items):
            if not items:
                return 0.5
            return sum(1.0 if item["result"] == 0 else 0.5 if item["result"] == 1 else 0.0 for item in items) / len(items)

        table = standings.get(team_id, {})
        return {
            "win_rate": (wins + draws * 0.5) / n if n else 0.45,
            "avg_goals": goals / n if n else 1.5,
            "avg_loss": conceded / n if n else 1.2,
            "avg_cards": cards / n if n else 1.5,
            "days_rest": days_rest,
            "form_5": form(form_5),
            "form_10": form(recent),
            "home_form_5": form(home_history),
            "away_form_5": form(away_history),
            "points_per_match": table.get("points", 0.0) / max(table.get("played", 0.0), 1.0),
            "goal_diff_per_match": table.get("goal_diff", 0.0) / max(table.get("played", 0.0), 1.0),
            "xg_5": sum(xg_values) / len(xg_values) if xg_values else 0.0,
            "xga_5": sum(xga_values) / len(xga_values) if xga_values else 0.0,
            "shots_5": sum(shots_values) / len(shots_values) if shots_values else 0.0,
            "on_target_5": sum(on_target_values) / len(on_target_values) if on_target_values else 0.0,
            "lineup_stability": sum(lineup_values) / len(lineup_values) if lineup_values else 0.0,
            "matches_14d": sum(1 for item in all_history if item.get("date") and 0 <= (match_dt - item["date"]).days <= 14),
            "rank": 0,
        }

    def rank_for(team_id: int) -> int:
        if not standings:
            return 0
        ordered = sorted(standings.items(), key=lambda pair: (
            -pair[1].get("points", 0), -pair[1].get("goal_diff", 0), -pair[1].get("goals_for", 0)
        ))
        for index, (candidate, _) in enumerate(ordered, start=1):
            if candidate == team_id:
                return index
        return len(ordered) + 1

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
        # football-data batches multiple seasons. Reset league-table points at
        # the long off-season boundary while retaining ELO/history continuity.
        if previous_global_dt is not None and (match_dt - previous_global_dt).days > 45:
            standings.clear()
        previous_global_dt = match_dt
        home_feat = calc_team(home_id, match_dt, (match.get("homeTeam") or {}).get("name"))
        away_feat = calc_team(away_id, match_dt, (match.get("awayTeam") or {}).get("name"))
        home_rank = rank_for(home_id)
        away_rank = rank_for(away_id)
        home_elo = get_elo(home_id) + ELO_HOME_ADVANTAGE
        away_elo = get_elo(away_id)
        home_detail, away_detail = lookup_enrichment(match)
        prematch_detail = lookup_prematch(match)
        pair_key = tuple(sorted((home_id, away_id)))
        h2h = h2h_stats.get(pair_key, [])[-5:]
        h2h_home_wins = sum(1 for item in h2h if item == home_id)
        h2h_draws = sum(1 for item in h2h if item == 0)
        h2h_away_wins = sum(1 for item in h2h if item == away_id)

        records.append({
            "_match_id": match.get("id"),
            "_match_date": match_dt.isoformat(),
            "_competition": competition_code or match.get("competition", {}).get("code", ""),
            # Kept outside FEATURE_NAMES for audit/reporting only.  These
            # identity fields never enter the model and make sample coverage
            # explainable without reconstructing the raw provider payload.
            "_home_team": (home.get("name") or "").strip(),
            "_away_team": (away.get("name") or "").strip(),
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
            "home_rank": home_rank,
            "away_rank": away_rank,
            "rank_diff": home_rank - away_rank,
            "home_points_per_match": round(home_feat["points_per_match"], 4),
            "away_points_per_match": round(away_feat["points_per_match"], 4),
            "points_per_match_diff": round(home_feat["points_per_match"] - away_feat["points_per_match"], 4),
            "home_goal_diff_per_match": round(home_feat["goal_diff_per_match"], 4),
            "away_goal_diff_per_match": round(away_feat["goal_diff_per_match"], 4),
            "goal_diff_per_match_diff": round(home_feat["goal_diff_per_match"] - away_feat["goal_diff_per_match"], 4),
            "home_form_5": round(home_feat["form_5"], 4),
            "away_form_5": round(away_feat["form_5"], 4),
            "home_form_10": round(home_feat["form_10"], 4),
            "away_form_10": round(away_feat["form_10"], 4),
            "home_home_form_5": round(home_feat["home_form_5"], 4),
            "away_away_form_5": round(away_feat["away_form_5"], 4),
            "home_matches_14d": home_feat["matches_14d"],
            "away_matches_14d": away_feat["matches_14d"],
            "matches_14d_diff": home_feat["matches_14d"] - away_feat["matches_14d"],
            "home_xg_5": round(home_feat["xg_5"], 4),
            "away_xg_5": round(away_feat["xg_5"], 4),
            "home_xga_5": round(home_feat["xga_5"], 4),
            "away_xga_5": round(away_feat["xga_5"], 4),
            "home_shots_5": round(home_feat["shots_5"], 4),
            "away_shots_5": round(away_feat["shots_5"], 4),
            "home_shots_on_target_5": round(home_feat["on_target_5"], 4),
            "away_shots_on_target_5": round(away_feat["on_target_5"], 4),
            # These values are strictly pre-kickoff snapshots. Missing provider
            # fields remain neutral and are audited by the zero-rate report.
            "home_lineup_stability": prematch_detail.get("home_lineup_stability") or home_feat.get("lineup_stability", 0.0),
            "away_lineup_stability": prematch_detail.get("away_lineup_stability") or away_feat.get("lineup_stability", 0.0),
            "home_injury_impact": prematch_detail.get("home_injury_impact", 0.0),
            "away_injury_impact": prematch_detail.get("away_injury_impact", 0.0),
            "market_home_prob": prematch_detail.get("market_home_prob", 0.0),
            "market_draw_prob": prematch_detail.get("market_draw_prob", 0.0),
            "market_away_prob": prematch_detail.get("market_away_prob", 0.0),
            "label": label,
        })

        home_xg = float(home_detail.get("xg", 0.0)) or nested_numeric(match, ("home_xg", "homeExpectedGoals", "expected_goals_home"))
        away_xg = float(away_detail.get("xg", 0.0)) or nested_numeric(match, ("away_xg", "awayExpectedGoals", "expected_goals_away"))
        home_shots = float(home_detail.get("shots", 0.0)) or nested_numeric(match, ("home_shots", "homeTotalShots", "total_shots_home"))
        away_shots = float(away_detail.get("shots", 0.0)) or nested_numeric(match, ("away_shots", "awayTotalShots", "total_shots_away"))
        home_on_target = float(home_detail.get("on_target", 0.0)) or nested_numeric(match, ("home_shots_on_target", "homeShotsOnGoal", "shots_on_goal_home"))
        away_on_target = float(away_detail.get("on_target", 0.0)) or nested_numeric(match, ("away_shots_on_target", "awayShotsOnGoal", "shots_on_goal_away"))
        # Zero is treated as missing for provider stats, so a provider cannot
        # accidentally contribute a target match's final value to its own row.
        home_item = {"result": label, "goals": home_goals, "conceded": away_goals,
                     "cards": 1.5, "xg": home_xg or None, "xga": away_xg or None,
                     "shots": home_shots or None, "on_target": home_on_target or None,
                     "lineup_stability": home_detail.get("lineup_stability"),
                     "home": True, "date": match_dt}
        away_item = {"result": 2 - label if label != 1 else 1, "goals": away_goals, "conceded": home_goals,
                     "cards": 1.5, "xg": away_xg or None, "xga": home_xg or None,
                     "shots": away_shots or None, "on_target": away_on_target or None,
                     "lineup_stability": away_detail.get("lineup_stability"),
                     "home": False, "date": match_dt}
        team_stats.setdefault(home_id, []).append(home_item)
        team_stats.setdefault(away_id, []).append(away_item)
        home_table = standings.setdefault(home_id, {"played": 0.0, "points": 0.0, "goal_diff": 0.0, "goals_for": 0.0})
        away_table = standings.setdefault(away_id, {"played": 0.0, "points": 0.0, "goal_diff": 0.0, "goals_for": 0.0})
        home_table["played"] += 1; away_table["played"] += 1
        home_table["goals_for"] += home_goals; away_table["goals_for"] += away_goals
        home_table["goal_diff"] += home_goals - away_goals; away_table["goal_diff"] += away_goals - home_goals
        home_table["points"] += 3 if label == 0 else 1 if label == 1 else 0
        away_table["points"] += 3 if label == 2 else 1 if label == 1 else 0
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

        optional = {key: 0.0 for key in OPTIONAL_PREMATCH_FEATURES} if USE_OPTIONAL_PREMATCH_FEATURES else {}
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
            **optional,
            "label": label
        })

    # Synthetic mode is only a development fallback; still emit the exact
    # production feature contract so a smoke training run cannot silently
    # drop newly added rolling features.
    for record in records:
        for name in FEATURE_NAMES:
            record.setdefault(name, 0.0)
    return pd.DataFrame(records)


# ==================== 训练 ====================

def multiclass_brier(y_true: np.ndarray, probabilities: np.ndarray) -> float:
    one_hot = np.zeros_like(probabilities)
    one_hot[np.arange(len(y_true)), y_true.astype(int)] = 1.0
    return float(np.mean(np.sum((probabilities - one_hot) ** 2, axis=1)))


def expected_calibration_error(y_true: np.ndarray, probabilities: np.ndarray, bins: int = 10) -> float:
    """Multiclass ECE on the held-out window, used for honest confidence UI."""
    confidence = probabilities.max(axis=1)
    predictions = probabilities.argmax(axis=1)
    ece = 0.0
    for lower in np.linspace(0.0, 1.0, bins + 1)[:-1]:
        upper = min(1.0, lower + 1.0 / bins)
        mask = (confidence >= lower) & ((confidence < upper) if upper < 1.0 else (confidence <= upper))
        if not np.any(mask):
            continue
        ece += float(mask.mean()) * abs(float(confidence[mask].mean()) - float((predictions[mask] == y_true[mask]).mean()))
    return float(ece)


def _quality_name(value: object) -> str:
    value = str(value or "").strip().lower()
    return re.sub(r"[^a-z0-9\u4e00-\u9fff]+", "", value)


def training_quality_report(df: pd.DataFrame) -> dict:
    """Emit data-quality facts used by both model review and operations.

    This is deliberately descriptive rather than a training filter: the
    existing candidate/promotion gates decide whether a model may ship.  A
    missing xG snapshot is reported explicitly and never replaced by a fake
    value.
    """
    if df is None or df.empty:
        return {"rows": 0, "duplicates": 0, "invalid": 0, "xg_coverage": 0.0, "team_sample_tiers": {}}
    work = df.copy()
    dates = pd.to_datetime(work.get("_match_date"), errors="coerce") if "_match_date" in work else pd.Series(pd.NaT, index=work.index)
    competitions = work.get("_competition", pd.Series("unknown", index=work.index)).fillna("unknown").astype(str).str.upper()
    home = work.get("_home_team", pd.Series("", index=work.index)).map(_quality_name)
    away = work.get("_away_team", pd.Series("", index=work.index)).map(_quality_name)
    keys = competitions + "|" + dates.dt.strftime("%Y-%m-%d").fillna("unknown") + "|" + home + "|" + away
    duplicates = int(keys.duplicated(keep=False).sum())
    invalid = int((dates.isna() | home.eq("") | away.eq("")).sum())
    xg_mask = pd.Series(True, index=work.index)
    for name in ("home_xg_5", "away_xg_5", "home_xga_5", "away_xga_5"):
        if name in work:
            xg_mask &= pd.to_numeric(work[name], errors="coerce").fillna(0).gt(0)
        else:
            xg_mask &= False

    per_comp = {}
    for code, part in work.groupby(competitions):
        part_xg = xg_mask.loc[part.index]
        part_dates = dates.loc[part.index]
        per_comp[str(code)] = {
            "matches": int(len(part)),
            "date_min": None if part_dates.dropna().empty else part_dates.min().date().isoformat(),
            "date_max": None if part_dates.dropna().empty else part_dates.max().date().isoformat(),
            "xg_complete": int(part_xg.sum()),
            "xg_coverage": round(float(part_xg.mean()) if len(part) else 0.0, 4),
            "duplicate_rows": int(keys.loc[part.index].duplicated(keep=False).sum()),
        }
    sample_counts: dict[str, int] = {}
    for league, team in zip(competitions, home):
        if team: sample_counts[f"{league}|{team}"] = sample_counts.get(f"{league}|{team}", 0) + 1
    for league, team in zip(competitions, away):
        if team: sample_counts[f"{league}|{team}"] = sample_counts.get(f"{league}|{team}", 0) + 1
    tiers = {"robust": 0, "ready": 0, "limited": 0, "insufficient": 0}
    for count in sample_counts.values():
        if count >= 10: tiers["robust"] += 1
        elif count >= 5: tiers["ready"] += 1
        elif count >= 3: tiers["limited"] += 1
        else: tiers["insufficient"] += 1
    return {
        "rows": int(len(work)),
        "date_min": None if dates.dropna().empty else dates.min().date().isoformat(),
        "date_max": None if dates.dropna().empty else dates.max().date().isoformat(),
        "duplicates": duplicates,
        "invalid": invalid,
        "xg_complete_rows": int(xg_mask.sum()),
        "xg_coverage": round(float(xg_mask.mean()), 4),
        "team_count": int(len(sample_counts)),
        "team_sample_tiers": {"thresholds": {"robust": 10, "ready": 5, "limited": 3}, **tiers},
        "by_competition": per_comp,
    }


def select_abstain_threshold(y_true: np.ndarray, probabilities: np.ndarray) -> float:
    """Choose a conservative threshold from validation, not from test data."""
    best = 0.0
    for threshold in np.linspace(0.34, 0.70, 19):
        mask = probabilities.max(axis=1) >= threshold
        if mask.sum() < max(30, int(len(y_true) * 0.05)):
            continue
        precision = float((probabilities[mask].argmax(axis=1) == y_true[mask]).mean())
        if precision >= 0.55:
            best = float(threshold)
    return round(best, 3)


def temperature_scale(probabilities: np.ndarray, temperature: float) -> np.ndarray:
    clipped = np.clip(probabilities, 1e-6, 1.0)
    logits = np.log(clipped)
    logits = logits / max(float(temperature), 1e-3)
    logits -= logits.max(axis=1, keepdims=True)
    exp_logits = np.exp(logits)
    return exp_logits / exp_logits.sum(axis=1, keepdims=True)


def class_bias_scale(probabilities: np.ndarray, bias: list[float] | tuple[float, float, float]) -> np.ndarray:
    """Apply a validation-fitted class prior without changing feature values."""
    values = np.clip(np.asarray(probabilities, dtype=float), 1e-6, 1.0)
    logits = np.log(values) + np.asarray(bias, dtype=float)
    logits -= logits.max(axis=-1, keepdims=True)
    exp_logits = np.exp(logits)
    return exp_logits / exp_logits.sum(axis=-1, keepdims=True)


def select_class_bias(y_true: np.ndarray, probabilities: np.ndarray) -> list[float]:
    """Tune small outcome priors on validation only; never use test labels."""
    best = (float("-inf"), [0.0, 0.0, 0.0])
    grid = np.linspace(-0.18, 0.18, 7)
    for home_bias in grid:
        for draw_bias in grid:
            candidate = class_bias_scale(probabilities, [home_bias, draw_bias, 0.0])
            metrics = prediction_metrics(y_true, candidate)
            report = classification_report(y_true, candidate.argmax(axis=1), output_dict=True, zero_division=0)
            draw_recall = float(report.get("1", {}).get("recall", 0.0))
            score = metrics["accuracy"] + 0.03 * metrics["balanced_accuracy"] + 0.01 * draw_recall
            if score > best[0]:
                best = (score, [round(float(home_bias), 4), round(float(draw_bias), 4), 0.0])
    return best[1]


def prediction_metrics(y_true: np.ndarray, probabilities: np.ndarray) -> dict[str, float]:
    """Return the metrics used by the promotion gate in one place."""
    predictions = np.argmax(probabilities, axis=1)
    return {
        "accuracy": float(accuracy_score(y_true, predictions)),
        "balanced_accuracy": float(balanced_accuracy_score(y_true, predictions)),
        "log_loss": float(log_loss(y_true, probabilities, labels=[0, 1, 2])),
        "brier_score": float(multiclass_brier(y_true, probabilities)),
        "expected_calibration_error": float(expected_calibration_error(y_true, probabilities)),
    }


def walk_forward_report(ordered: pd.DataFrame, X: np.ndarray, y: np.ndarray,
                        temperature: float) -> list[dict[str, float | int]]:
    """Evaluate the selected transparent baseline over every available year.

    The ELO values are generated chronologically while building the dataset, so
    this report is a useful leakage check as well as a stability check. The
    temperature is fitted on the validation season and then held fixed here.
    """
    if "_match_date" not in ordered.columns:
        return []
    dates = pd.to_datetime(ordered["_match_date"], errors="coerce")
    rows: list[dict[str, float | int]] = []
    for year in sorted(dates.dt.year.dropna().unique().tolist()):
        mask = dates.dt.year.to_numpy() == year
        if int(mask.sum()) < 30:
            continue
        raw = elo_probabilities(X[mask])
        calibrated = temperature_scale(raw, temperature)
        metrics = prediction_metrics(y[mask], calibrated)
        rows.append({"year": int(year), "samples": int(mask.sum()), **{
            key: round(float(value), 4) for key, value in metrics.items()
        }})
    return rows


def elo_probabilities(X: np.ndarray) -> np.ndarray:
    """可解释的 ELO+平局基线，用于判断复杂模型是否真的带来增益。"""
    elo_diff = X[:, FEATURE_NAMES.index("elo_diff")]
    home = 1 / (1 + 10 ** (-elo_diff / 400))
    draw = np.clip(0.26 - np.abs(elo_diff) / 2400, 0.08, 0.28)
    home = home * (1 - draw)
    away = (1 - (home / np.maximum(1 - draw, 1e-8))) * (1 - draw)
    probabilities = np.column_stack([home, draw, away])
    return probabilities / probabilities.sum(axis=1, keepdims=True)


def poisson_probabilities(X: np.ndarray) -> np.ndarray:
    """用滚动进失球率构造可解释的 Poisson 进球分布基线。"""
    h_attack = X[:, FEATURE_NAMES.index("home_avg_goals")]
    a_attack = X[:, FEATURE_NAMES.index("away_avg_goals")]
    h_defence = X[:, FEATURE_NAMES.index("home_avg_loss")]
    a_defence = X[:, FEATURE_NAMES.index("away_avg_loss")]
    home_lambda = np.clip(0.58 * h_attack + 0.42 * a_defence + 0.12, 0.2, 4.5)
    away_lambda = np.clip(0.58 * a_attack + 0.42 * h_defence, 0.15, 4.0)
    probabilities = []
    for home_mean, away_mean in zip(home_lambda, away_lambda):
        home_goals = np.array([np.exp(-home_mean) * home_mean ** k / math.factorial(k) for k in range(9)])
        away_goals = np.array([np.exp(-away_mean) * away_mean ** k / math.factorial(k) for k in range(9)])
        matrix = np.outer(home_goals, away_goals)
        home_win = np.tril(matrix, -1).sum()
        draw = np.trace(matrix)
        away_win = np.triu(matrix, 1).sum()
        row = np.array([home_win, draw, away_win], dtype=float)
        probabilities.append(row / max(row.sum(), 1e-8))
    return np.asarray(probabilities)


def build_xgb() -> xgb.XGBClassifier:
    return xgb.XGBClassifier(
        n_estimators=350,
        max_depth=4,
        learning_rate=0.035,
        subsample=0.85,
        colsample_bytree=0.85,
        min_child_weight=5,
        gamma=0.15,
        reg_alpha=0.05,
        reg_lambda=1.2,
        objective="multi:softprob",
        num_class=3,
        eval_metric="mlogloss",
        random_state=42,
        n_jobs=-1,
        tree_method="hist"
    )


def build_catboost() -> "CatBoostClassifier | None":
    """Create a numeric CatBoost challenger without changing the active model."""
    if CatBoostClassifier is None or not ENABLE_CATBOOST_CANDIDATE:
        return None
    return CatBoostClassifier(
        iterations=450,
        depth=6,
        learning_rate=0.035,
        loss_function="MultiClass",
        eval_metric="MultiClass",
        l2_leaf_reg=6.0,
        random_seed=42,
        verbose=False,
        allow_writing_files=False,
        thread_count=max(1, (os.cpu_count() or 2) - 1),
    )


def train_xgboost(df: pd.DataFrame):
    """时间切分训练 XGBoost + Logistic 混合模型，并选择验证集上的最佳融合权重。"""
    print(f"\n[Train] Dataset size: {len(df)}")
    if len(df) < 300:
        raise RuntimeError("训练样本过少，至少需要 300 条真实比赛记录")

    ordered = df.copy()
    if "_match_date" in ordered.columns:
        ordered["_match_date"] = pd.to_datetime(ordered["_match_date"], errors="coerce")
        ordered = ordered.sort_values("_match_date", kind="stable")
    dedupe_columns = [c for c in ["_match_id", "_match_date"] if c in ordered.columns]
    if dedupe_columns:
        ordered = ordered.drop_duplicates(subset=dedupe_columns)
    X = ordered[FEATURE_NAMES].astype(float).values
    y = ordered["label"].astype(int).values
    n = len(ordered)
    split_name = "chronological_70_15_15"
    if "_match_date" in ordered.columns and ordered["_match_date"].notna().all():
        years = ordered["_match_date"].dt.year.to_numpy()
        unique_years = sorted(np.unique(years).tolist())
    else:
        years = None
        unique_years = []
    if len(unique_years) >= 3:
        train_years = set(unique_years[:-2])
        valid_year = unique_years[-2]
        test_year = unique_years[-1]
        train_mask = np.isin(years, list(train_years))
        valid_mask = years == valid_year
        test_mask = years == test_year
        split_name = f"season_walk_forward_{','.join(map(str, sorted(train_years)))}_to_{valid_year}_to_{test_year}"
        X_train, y_train = X[train_mask], y[train_mask]
        X_valid, y_valid = X[valid_mask], y[valid_mask]
        X_test, y_test = X[test_mask], y[test_mask]
    else:
        train_end = max(int(n * 0.70), 200)
        valid_end = max(int(n * 0.85), train_end + 50)
        valid_end = min(valid_end, n - 1)
        X_train, y_train = X[:train_end], y[:train_end]
        X_valid, y_valid = X[train_end:valid_end], y[train_end:valid_end]
        X_test, y_test = X[valid_end:], y[valid_end:]
    print(f"[Train] {split_name}: train={len(X_train)}, valid={len(X_valid)}, test={len(X_test)}")
    class_counts = np.bincount(y_train, minlength=3).astype(float)
    class_weights = {label: len(y_train) / (3 * max(count, 1.0)) for label, count in enumerate(class_counts)}
    train_weights = np.asarray([class_weights[label] for label in y_train])

    scaler = StandardScaler()
    X_train_scaled = scaler.fit_transform(X_train)
    X_valid_scaled = scaler.transform(X_valid)
    logistic = LogisticRegression(max_iter=1500, C=0.35, class_weight="balanced", random_state=42)
    logistic.fit(X_train_scaled, y_train)
    xgb_model = build_xgb()
    xgb_model.fit(X_train, y_train, sample_weight=train_weights)
    catboost_model = build_catboost()
    if catboost_model is not None:
        try:
            catboost_model.fit(X_train, y_train, sample_weight=train_weights)
            print("[Train] CatBoost challenger fitted")
        except Exception as exc:
            print(f"[WARN] CatBoost challenger skipped: {exc}")
            catboost_model = None

    xgb_valid = xgb_model.predict_proba(X_valid)
    logistic_valid = logistic.predict_proba(X_valid_scaled)
    catboost_valid = catboost_model.predict_proba(X_valid) if catboost_model is not None else None
    # Do not let a hand-set draw penalty decide the production recipe. It is
    # useful for reporting draw recall, but weighting validation loss made the
    # previous model overfit the rare draw class and lose overall accuracy.
    validation_loss_weights = np.ones_like(y_valid, dtype=float)
    selection_objective = "logloss + 0.15*(1-accuracy) + 0.05*brier"
    candidates = []
    for weight in np.linspace(0, 1, 11):
        probabilities = weight * xgb_valid + (1 - weight) * logistic_valid
        ll = log_loss(y_valid, probabilities, labels=[0, 1, 2])
        acc = accuracy_score(y_valid, probabilities.argmax(axis=1))
        brier = multiclass_brier(y_valid, probabilities)
        candidates.append((ll + 0.15 * (1 - acc) + 0.05 * brier, float(weight), ll))
    _, blend_weight, blend_valid_logloss = min(candidates, key=lambda item: item[0])
    print(f"[Train] selected XGBoost blend weight={blend_weight:.1f}, validation logloss={blend_valid_logloss:.4f}")

    # CatBoost is evaluated as a challenger against the existing XGBoost+
    # Logistic blend. It can only enter the recipe when validation improves;
    # otherwise the active recipe remains unchanged.
    catboost_weight = 0.0
    if catboost_valid is not None:
        base_valid = blend_weight * xgb_valid + (1 - blend_weight) * logistic_valid
        cat_candidates = []
        for weight in np.linspace(0, 1, 11):
            candidate = weight * catboost_valid + (1 - weight) * base_valid
            ll = log_loss(y_valid, candidate, labels=[0, 1, 2])
            acc = accuracy_score(y_valid, candidate.argmax(axis=1))
            brier = multiclass_brier(y_valid, candidate)
            cat_candidates.append((ll + 0.15 * (1 - acc) + 0.05 * brier, float(weight), ll))
        base_score = min((item[0] for item in cat_candidates if item[1] == 0.0), default=float("inf"))
        best_cat_score, candidate_cat_weight, _ = min(cat_candidates, key=lambda item: item[0])
        if best_cat_score + 0.001 < base_score:
            catboost_weight = candidate_cat_weight
        print(f"[Train] CatBoost challenger weight={catboost_weight:.1f}")

    # 最终模型使用 train+validation，测试集只用于一次最终评估。
    X_fit = np.concatenate([X_train, X_valid], axis=0)
    y_fit = np.concatenate([y_train, y_valid], axis=0)
    final_scaler = StandardScaler().fit(X_fit)
    final_logistic = LogisticRegression(max_iter=1500, C=0.35, class_weight="balanced", random_state=42)
    final_logistic.fit(final_scaler.transform(X_fit), y_fit)
    final_xgb = build_xgb()
    fit_counts = np.bincount(y_fit, minlength=3).astype(float)
    fit_weights = np.asarray([len(y_fit) / (3 * max(fit_counts[label], 1.0)) for label in y_fit])
    final_xgb.fit(X_fit, y_fit, sample_weight=fit_weights)
    final_catboost = build_catboost()
    if final_catboost is not None:
        try:
            final_catboost.fit(X_fit, y_fit, sample_weight=fit_weights)
        except Exception as exc:
            print(f"[WARN] final CatBoost challenger skipped: {exc}")
            final_catboost = None
            catboost_weight = 0.0
    xgb_test = final_xgb.predict_proba(X_test)
    logistic_test = final_logistic.predict_proba(final_scaler.transform(X_test))
    base_model_test = blend_weight * xgb_test + (1 - blend_weight) * logistic_test
    base_model_valid = blend_weight * xgb_valid + (1 - blend_weight) * logistic_valid
    catboost_test = final_catboost.predict_proba(X_test) if final_catboost is not None else None
    model_probabilities = (catboost_weight * catboost_test + (1 - catboost_weight) * base_model_test
                           if catboost_test is not None else base_model_test)
    model_valid = (catboost_weight * catboost_valid + (1 - catboost_weight) * base_model_valid
                   if catboost_valid is not None else base_model_valid)
    elo_valid = elo_probabilities(X_valid)
    elo_test = elo_probabilities(X_test)
    poisson_valid = poisson_probabilities(X_valid)
    poisson_test = poisson_probabilities(X_test)
    # 再在可解释基线与机器学习模型之间选择配方，防止复杂模型在新赛季退化时强行上线。
    recipe_candidates = []
    for elo_weight in np.linspace(0, 1, 11):
        for poisson_weight in np.linspace(0, 1 - elo_weight, 11):
            model_weight = 1 - elo_weight - poisson_weight
            candidate = elo_weight * elo_valid + poisson_weight * poisson_valid + model_weight * model_valid
            ll = log_loss(y_valid, candidate, labels=[0, 1, 2])
            acc = accuracy_score(y_valid, candidate.argmax(axis=1))
            brier = multiclass_brier(y_valid, candidate)
            recipe_candidates.append((ll + 0.15 * (1 - acc) + 0.05 * brier, float(elo_weight), float(poisson_weight), float(model_weight), ll))
    _, elo_weight, poisson_weight, model_weight, valid_logloss = min(recipe_candidates, key=lambda item: item[0])
    valid_recipe = elo_weight * elo_valid + poisson_weight * poisson_valid + model_weight * model_valid
    temperature_candidates = [(log_loss(y_valid, temperature_scale(valid_recipe, t), labels=[0, 1, 2]), float(t)) for t in np.linspace(0.70, 1.80, 23)]
    _, calibration_temperature = min(temperature_candidates, key=lambda item: item[0])
    calibrated_valid = temperature_scale(valid_recipe, calibration_temperature)
    abstain_threshold = select_abstain_threshold(y_valid, calibrated_valid)
    raw_probabilities = elo_weight * elo_test + poisson_weight * poisson_test + model_weight * model_probabilities
    hybrid_probabilities = temperature_scale(raw_probabilities, calibration_temperature)

    # Calibrate the transparent ELO baseline independently. This avoids
    # allowing a validation-selected blend to replace a stronger baseline on a
    # new season merely because it has more parameters.
    elo_temperature_candidates = [
        (log_loss(y_valid, temperature_scale(elo_valid, t), labels=[0, 1, 2]), float(t))
        for t in np.linspace(0.70, 1.80, 23)
    ]
    _, elo_calibration_temperature = min(elo_temperature_candidates, key=lambda item: item[0])
    calibrated_elo_valid = temperature_scale(elo_valid, elo_calibration_temperature)
    calibrated_elo_test = temperature_scale(elo_test, elo_calibration_temperature)
    hybrid_valid_metrics = prediction_metrics(y_valid, calibrated_valid)
    calibrated_elo_valid_metrics = prediction_metrics(y_valid, calibrated_elo_valid)

    # Model selection is done on the validation season only. Prefer the simpler
    # calibrated ELO strategy when it is no worse on the validation objective;
    # this reduces variance and keeps the production explanation auditable.
    def selection_score(metrics: dict[str, float]) -> float:
        return metrics["log_loss"] + 0.15 * (1 - metrics["accuracy"]) + 0.05 * metrics["brier_score"]

    elo_accuracy_edge = calibrated_elo_valid_metrics["accuracy"] - hybrid_valid_metrics["accuracy"]
    elo_calibration_cost = calibrated_elo_valid_metrics["log_loss"] - hybrid_valid_metrics["log_loss"]
    elo_brier_cost = calibrated_elo_valid_metrics["brier_score"] - hybrid_valid_metrics["brier_score"]
    if (
        selection_score(calibrated_elo_valid_metrics) <= selection_score(hybrid_valid_metrics) + 0.002
        or (
            # Accuracy is the user-visible outcome. Allow the transparent
            # baseline to win when it has a meaningful validation edge and its
            # probability quality does not materially regress.
            elo_accuracy_edge >= 0.002
            and elo_calibration_cost <= 0.015
            and elo_brier_cost <= 0.005
        )
    ):
        strategy = "elo-calibrated-v3"
        probabilities = calibrated_elo_test
        selected_temperature = elo_calibration_temperature
        selected_elo_weight, selected_poisson_weight, selected_model_weight = 1.0, 0.0, 0.0
    else:
        strategy = "hybrid-xgb-logreg-elo-poisson-v3"
        probabilities = hybrid_probabilities
        selected_temperature = calibration_temperature
        selected_elo_weight, selected_poisson_weight, selected_model_weight = elo_weight, poisson_weight, model_weight

    abstain_threshold = select_abstain_threshold(
        y_valid,
        calibrated_elo_valid if strategy == "elo-calibrated-v3" else calibrated_valid,
    )
    selected_validation_probabilities = calibrated_elo_valid if strategy == "elo-calibrated-v3" else calibrated_valid
    class_bias = select_class_bias(y_valid, selected_validation_probabilities)
    probabilities = class_bias_scale(probabilities, class_bias)

    predictions = np.argmax(probabilities, axis=1)
    baseline = elo_test
    baseline_pred = np.argmax(baseline, axis=1)
    selected_metrics = prediction_metrics(y_test, probabilities)
    baseline_metrics = prediction_metrics(y_test, baseline)
    hybrid_metrics = prediction_metrics(y_test, hybrid_probabilities)
    calibrated_elo_metrics = prediction_metrics(y_test, calibrated_elo_test)
    catboost_metrics = prediction_metrics(y_test, catboost_test) if catboost_test is not None else None

    acc = selected_metrics["accuracy"]
    baseline_acc = baseline_metrics["accuracy"]
    hybrid_logloss = selected_metrics["log_loss"]
    baseline_logloss = baseline_metrics["log_loss"]
    hybrid_brier = selected_metrics["brier_score"]
    baseline_brier = baseline_metrics["brier_score"]
    hybrid_ece = selected_metrics["expected_calibration_error"]
    baseline_ece = baseline_metrics["expected_calibration_error"]
    walk_forward = walk_forward_report(ordered, X, y, selected_temperature)
    # The first season is a cold-start window with no historical form. It is
    # reported for transparency but excluded from the production stability
    # gate; subsequent seasons must all clear the accuracy floor.
    stability_windows = walk_forward[1:] if len(walk_forward) >= 4 else walk_forward
    stability_passed = bool(stability_windows) and min(row["accuracy"] for row in stability_windows) >= 0.50
    report = classification_report(y_test, predictions, target_names=["HOME_WIN", "DRAW", "AWAY_WIN"], output_dict=True, zero_division=0)
    importance_df = pd.DataFrame({"feature": FEATURE_NAMES, "importance": final_xgb.feature_importances_}).sort_values("importance", ascending=False)
    def coverage(names: list[str]) -> float:
        present = [pd.to_numeric(ordered[name], errors="coerce").fillna(0).abs() > 1e-9
                   for name in names if name in ordered.columns]
        return round(float(pd.concat(present, axis=1).any(axis=1).mean()), 4) if present else 0.0

    enrichment_coverage = {
        "xg_or_xga": coverage(["home_xg_5", "away_xg_5", "home_xga_5", "away_xga_5"]),
        "shots": coverage(["home_shots_5", "away_shots_5", "home_shots_on_target_5", "away_shots_on_target_5"]),
        "lineups": coverage(["home_lineup_stability", "away_lineup_stability"]),
        "injuries": coverage(["home_injury_impact", "away_injury_impact"]),
        "odds": coverage(["market_home_prob", "market_draw_prob", "market_away_prob"]),
    }
    data_quality = training_quality_report(df)
    train_results = {
        "production_gate_version": 2,
        "trained_at": datetime.now().isoformat(),
        "dataset_size": int(n),
        "train_size": int(len(X_train)),
        "validation_size": int(len(X_valid)),
        "test_size": int(len(X_test)),
        "accuracy": round(float(acc), 4),
        "baseline_accuracy": round(float(baseline_acc), 4),
        "balanced_accuracy": round(float(balanced_accuracy_score(y_test, predictions)), 4),
        "precision": round(float(precision_score(y_test, predictions, average="weighted", zero_division=0)), 4),
        "recall": round(float(recall_score(y_test, predictions, average="weighted", zero_division=0)), 4),
        "f1": round(float(f1_score(y_test, predictions, average="weighted", zero_division=0)), 4),
        "log_loss": round(float(hybrid_logloss), 4),
        "baseline_log_loss": round(float(baseline_logloss), 4),
        "brier_score": round(float(hybrid_brier), 4),
        "baseline_brier_score": round(float(baseline_brier), 4),
        "expected_calibration_error": round(float(hybrid_ece), 4),
        "baseline_expected_calibration_error": round(float(baseline_ece), 4),
        "abstain_threshold": abstain_threshold,
        "feature_missing_rate": {
            name: round(float(pd.to_numeric(ordered[name], errors="coerce").isna().mean()), 4)
            for name in FEATURE_NAMES
        },
        "feature_zero_rate": {
            name: round(float((pd.to_numeric(ordered[name], errors="coerce").fillna(0) == 0).mean()), 4)
            for name in FEATURE_NAMES
        },
        "enrichment_coverage": enrichment_coverage,
        "data_quality": data_quality,
        "validation_log_loss": round(float(valid_logloss), 4),
        "validation_draw_loss_weight": round(float(DRAW_LOSS_WEIGHT), 2),
        "selection_objective": selection_objective,
        "strategy": strategy,
        "validation_strategy_metrics": {
            "hybrid": {key: round(float(value), 4) for key, value in hybrid_valid_metrics.items()},
            "calibrated_elo": {key: round(float(value), 4) for key, value in calibrated_elo_valid_metrics.items()},
        },
        "test_strategy_metrics": {
            "selected": {key: round(float(value), 4) for key, value in selected_metrics.items()},
            "hybrid": {key: round(float(value), 4) for key, value in hybrid_metrics.items()},
            "calibrated_elo": {key: round(float(value), 4) for key, value in calibrated_elo_metrics.items()},
            "raw_elo": {key: round(float(value), 4) for key, value in baseline_metrics.items()},
            "catboost_challenger": ({key: round(float(value), 4) for key, value in catboost_metrics.items()}
                                    if catboost_metrics is not None else None),
        },
        "walk_forward": walk_forward,
        "stability": {
            "windows": len(walk_forward),
            "evaluated_windows": len(stability_windows),
            "warmup_excluded_windows": len(walk_forward) - len(stability_windows),
            "mean_accuracy": round(float(np.mean([row["accuracy"] for row in stability_windows])), 4) if stability_windows else None,
            "min_accuracy": round(float(np.min([row["accuracy"] for row in stability_windows])), 4) if stability_windows else None,
            "max_accuracy": round(float(np.max([row["accuracy"] for row in stability_windows])), 4) if stability_windows else None,
            "min_accuracy_gate": 0.49,
            "passed": stability_passed,
        },
        "elo_k_factor": round(float(ELO_K_FACTOR), 2),
        "elo_home_advantage": round(float(ELO_HOME_ADVANTAGE), 2),
        "calibration_temperature": round(float(selected_temperature), 4),
        "class_bias": class_bias,
        "blend_weight_xgboost": round(float(blend_weight), 2),
        "blend_weight_elo": round(float(selected_elo_weight), 2),
        "blend_weight_poisson": round(float(selected_poisson_weight), 2),
        "blend_weight_model": round(float(selected_model_weight), 2),
        "blend_weight_catboost": round(float(catboost_weight), 2),
        "catboost_enabled": bool(catboost_model is not None and final_catboost is not None),
        "split": split_name,
        "class_distribution": {str(int(k)): int(v) for k, v in zip(*np.unique(y, return_counts=True))},
        "classification_report": report,
        "feature_importance": [
            {"feature": row["feature"], "importance": round(float(row["importance"]), 4)}
            for _, row in importance_df.iterrows()
        ],
        "classes": ["HOME_WIN", "DRAW", "AWAY_WIN"],
        "class_recall": {
            name: round(float(report.get(name, {}).get("recall", 0.0)), 4)
            for name in ["HOME_WIN", "DRAW", "AWAY_WIN"]
        },
        "model_scope": {
            "leagueIds": TRAIN_LEAGUE_IDS,
            "competitions": FOOTBALL_DATA_CODES,
            "specialistCompetitions": list(SPECIALIST_COMPETITION_CODES),
            "genericCompetitions": [code for code in FOOTBALL_DATA_CODES if code not in SPECIALIST_COMPETITION_CODES],
            "seasons": TRAIN_SEASONS,
            "warning": "单场概率经过时间滚动校准；仍建议结合数据完整度，低置信度结果不应强行解读"
        }
    }

    model_path = os.path.join(MODEL_DIR, "xgboost_model.json")
    scaler_path = os.path.join(MODEL_DIR, "feature_scaler.joblib")
    bundle_path = os.path.join(MODEL_DIR, "hybrid_model.joblib")
    fnames_path = os.path.join(MODEL_DIR, "feature_names.txt")
    results_path = os.path.join(MODEL_DIR, "train_results.json")
    candidate_model_path = os.path.join(MODEL_DIR, "xgboost_model.candidate.json")
    candidate_scaler_path = os.path.join(MODEL_DIR, "feature_scaler.candidate.joblib")
    candidate_bundle_path = os.path.join(MODEL_DIR, "hybrid_model.candidate.joblib")
    candidate_fnames_path = os.path.join(MODEL_DIR, "feature_names.candidate.txt")
    candidate_results_path = os.path.join(MODEL_DIR, "train_results.candidate.json")

    # Candidate-first persistence: a run may only replace the active model when it
    # beats both the ELO baseline and the currently deployed model on all three
    # production metrics. Failed candidates remain available for audit/rollback.
    previous_report = {}
    if os.path.exists(results_path):
        try:
            with open(results_path, "r", encoding="utf-8") as f:
                previous_report = json.load(f)
        except Exception as exc:
            print(f"[WARN] unable to read active training report: {exc}")

    selected_balanced_accuracy = float(selected_metrics.get("balanced_accuracy", 0.0))
    baseline_balanced_accuracy = float(baseline_metrics.get("balanced_accuracy", 0.0))
    draw_recall = float(report.get("DRAW", {}).get("recall", 0.0))
    gate_checks = {
        # Require a meaningful edge instead of accepting a rounded tie with
        # the baseline.  This prevents a model that merely predicts the
        # majority class from entering production.
        "accuracy_vs_elo": float(acc) >= float(baseline_acc) + 0.005,
        "balanced_accuracy_vs_elo": selected_balanced_accuracy >= baseline_balanced_accuracy + 0.02,
        "draw_recall": draw_recall >= 0.15,
        "log_loss_vs_elo": float(hybrid_logloss) <= float(baseline_logloss),
        "brier_vs_elo": float(hybrid_brier) <= float(baseline_brier),
        "walk_forward_stability": stability_passed,
    }
    if previous_report:
        gate_checks.update({
            # Reports are persisted to four decimals; tolerate that rounding
            # noise so a deterministic retrain is not incorrectly rejected.
            "accuracy_vs_previous": float(acc) + 0.001 >= float(previous_report.get("accuracy", -1)),
            "log_loss_vs_previous": float(hybrid_logloss) <= float(previous_report.get("log_loss", float("inf"))) + 0.0002,
            "brier_vs_previous": float(hybrid_brier) <= float(previous_report.get("brier_score", float("inf"))) + 0.0002,
        })
    promotion_accepted = all(gate_checks.values())
    train_results["promotion"] = {
        "accepted": promotion_accepted,
        "gateVersion": 2,
        "checks": gate_checks,
        "decision": "ACCEPTED" if promotion_accepted else "REJECTED",
        "reason": "candidate passed baseline, previous-model and stability gates" if promotion_accepted else "candidate did not beat baseline, previous-model and/or stability gate"
    }

    joblib.dump(final_xgb, candidate_model_path)
    joblib.dump(final_scaler, candidate_scaler_path)
    joblib.dump({"xgb": final_xgb, "logistic": final_logistic, "scaler": final_scaler,
                 "catboost": final_catboost,
                 "blend_weight_xgboost": blend_weight, "blend_weight_elo": selected_elo_weight,
                 "blend_weight_poisson": selected_poisson_weight, "blend_weight_model": selected_model_weight,
                 "blend_weight_catboost": catboost_weight,
                 "calibration_temperature": selected_temperature,
                 "class_bias": class_bias,
                 "abstain_threshold": abstain_threshold,
                 "feature_names": FEATURE_NAMES,
                 "strategy": strategy}, candidate_bundle_path)
    with open(candidate_fnames_path, "w", encoding="utf-8") as f:
        f.write("\n".join(FEATURE_NAMES))
    with open(candidate_results_path, "w", encoding="utf-8") as f:
        json.dump(train_results, f, ensure_ascii=False, indent=2)

    if promotion_accepted:
        if os.path.exists(bundle_path):
            shutil.copy2(bundle_path, os.path.join(MODEL_DIR, "hybrid_model.previous.joblib"))
        if os.path.exists(results_path):
            shutil.copy2(results_path, os.path.join(MODEL_DIR, "train_results.previous.json"))
        if os.path.exists(model_path):
            shutil.copy2(model_path, os.path.join(MODEL_DIR, "xgboost_model.previous.json"))
        shutil.copy2(candidate_model_path, model_path)
        shutil.copy2(candidate_scaler_path, scaler_path)
        shutil.copy2(candidate_bundle_path, bundle_path)
        shutil.copy2(candidate_fnames_path, fnames_path)
        shutil.copy2(candidate_results_path, results_path)
        print("[PROMOTION] candidate accepted and deployed")
    else:
        print("[PROMOTION] candidate rejected; active model left unchanged")

    print(f"[Eval] strategy={strategy}, accuracy={acc:.4f}, ELO baseline={baseline_acc:.4f}, "
          f"logloss={hybrid_logloss:.4f} (baseline {baseline_logloss:.4f}), "
          f"brier={hybrid_brier:.4f} (baseline {baseline_brier:.4f})")
    print(f"[OK] Candidate hybrid model saved to {candidate_bundle_path}")
    return final_xgb, final_scaler, train_results


def train_league_specialists(df: pd.DataFrame) -> dict[str, dict]:
    """Train audited specialist bundles for the five Understat leagues.

    The global bundle remains the fallback for every competition. A specialist
    is only eligible for inference when its own latest-season holdout beats its
    league ELO baseline on accuracy, balanced accuracy, log loss and draw
    recall. This prevents a good global average from hiding a weak league.
    """
    reports: dict[str, dict] = {}
    if "_competition" not in df.columns:
        return reports
    feature_names = FEATURE_NAMES
    for code in SPECIALIST_COMPETITION_CODES:
        league_df = df[df["_competition"].astype(str).str.upper() == code].copy()
        if len(league_df) < 500 or "_match_date" not in league_df.columns:
            print(f"[Specialist] {code} skipped: samples={len(league_df)}")
            continue
        league_df["_match_date"] = pd.to_datetime(league_df["_match_date"], errors="coerce")
        league_df = league_df.dropna(subset=["_match_date"]).sort_values("_match_date", kind="stable")
        years = sorted(league_df["_match_date"].dt.year.unique().tolist())
        if len(years) >= 3:
            train_years, valid_year, test_year = years[:-2], years[-2], years[-1]
            train_df = league_df[league_df["_match_date"].dt.year.isin(train_years)]
            valid_df = league_df[league_df["_match_date"].dt.year == valid_year]
            test_df = league_df[league_df["_match_date"].dt.year == test_year]
        else:
            n = len(league_df)
            train_end, valid_end = max(int(n * 0.70), 250), max(int(n * 0.85), 350)
            train_df, valid_df, test_df = league_df.iloc[:train_end], league_df.iloc[train_end:valid_end], league_df.iloc[valid_end:]
            train_years, valid_year, test_year = years, "chronological", "chronological"
        if min(len(train_df), len(valid_df), len(test_df)) < 50:
            print(f"[Specialist] {code} skipped: split too small")
            continue

        X_train, y_train = train_df[feature_names].astype(float).values, train_df["label"].astype(int).values
        X_valid, y_valid = valid_df[feature_names].astype(float).values, valid_df["label"].astype(int).values
        X_test, y_test = test_df[feature_names].astype(float).values, test_df["label"].astype(int).values
        counts = np.bincount(y_train, minlength=3).astype(float)
        weights = np.asarray([len(y_train) / (3 * max(counts[label], 1.0)) for label in y_train])
        scaler = StandardScaler().fit(X_train)
        logistic = LogisticRegression(max_iter=1500, C=0.35, class_weight="balanced", random_state=42)
        logistic.fit(scaler.transform(X_train), y_train)
        xgb_model = build_xgb()
        xgb_model.fit(X_train, y_train, sample_weight=weights)

        xgb_valid = xgb_model.predict_proba(X_valid)
        logistic_valid = logistic.predict_proba(scaler.transform(X_valid))
        blend_candidates = []
        for weight in np.linspace(0, 1, 11):
            probs = weight * xgb_valid + (1 - weight) * logistic_valid
            metrics = prediction_metrics(y_valid, probs)
            score = metrics["log_loss"] + 0.20 * (1 - metrics["accuracy"]) + 0.05 * metrics["brier_score"]
            blend_candidates.append((score, float(weight), metrics))
        _, blend_weight, _ = min(blend_candidates, key=lambda item: item[0])
        model_valid = blend_weight * xgb_valid + (1 - blend_weight) * logistic_valid
        elo_valid = elo_probabilities(X_valid)
        # Specialist routing chooses between the learned model and the
        # transparent ELO baseline using validation only.
        recipe_candidates = []
        for elo_weight in np.linspace(0, 1, 21):
            probs = elo_weight * elo_valid + (1 - elo_weight) * model_valid
            metrics = prediction_metrics(y_valid, probs)
            score = metrics["log_loss"] + 0.20 * (1 - metrics["accuracy"]) + 0.05 * metrics["brier_score"]
            recipe_candidates.append((score, float(elo_weight), metrics))
        _, elo_weight, validation_metrics = min(recipe_candidates, key=lambda item: item[0])
        model_weight = 1.0 - elo_weight
        selected_valid = elo_weight * elo_valid + model_weight * model_valid
        temperature_candidates = [(log_loss(y_valid, temperature_scale(selected_valid, t), labels=[0, 1, 2]), float(t))
                                  for t in np.linspace(0.70, 1.80, 23)]
        _, temperature = min(temperature_candidates, key=lambda item: item[0])
        calibrated_valid = temperature_scale(selected_valid, temperature)
        class_bias = select_class_bias(y_valid, calibrated_valid)
        calibrated_valid = class_bias_scale(calibrated_valid, class_bias)
        validation_metrics = prediction_metrics(y_valid, calibrated_valid)

        X_fit = np.concatenate([X_train, X_valid], axis=0)
        y_fit = np.concatenate([y_train, y_valid], axis=0)
        final_scaler = StandardScaler().fit(X_fit)
        final_logistic = LogisticRegression(max_iter=1500, C=0.35, class_weight="balanced", random_state=42)
        final_logistic.fit(final_scaler.transform(X_fit), y_fit)
        final_xgb = build_xgb()
        fit_counts = np.bincount(y_fit, minlength=3).astype(float)
        fit_weights = np.asarray([len(y_fit) / (3 * max(fit_counts[label], 1.0)) for label in y_fit])
        final_xgb.fit(X_fit, y_fit, sample_weight=fit_weights)
        xgb_test = final_xgb.predict_proba(X_test)
        logistic_test = final_logistic.predict_proba(final_scaler.transform(X_test))
        model_test = blend_weight * xgb_test + (1 - blend_weight) * logistic_test
        elo_test = elo_probabilities(X_test)
        probabilities = temperature_scale(elo_weight * elo_test + model_weight * model_test, temperature)
        probabilities = class_bias_scale(probabilities, class_bias)
        selected_metrics = prediction_metrics(y_test, probabilities)
        baseline_metrics = prediction_metrics(y_test, elo_test)
        report = classification_report(y_test, probabilities.argmax(axis=1), target_names=["HOME_WIN", "DRAW", "AWAY_WIN"], output_dict=True, zero_division=0)
        draw_recall = float(report.get("DRAW", {}).get("recall", 0.0))
        checks = {
            "accuracy_vs_league_elo": selected_metrics["accuracy"] >= baseline_metrics["accuracy"] + 0.003,
            "balanced_accuracy_vs_league_elo": selected_metrics["balanced_accuracy"] >= baseline_metrics["balanced_accuracy"] + 0.005,
            "log_loss_vs_league_elo": selected_metrics["log_loss"] <= baseline_metrics["log_loss"],
            "draw_recall": draw_recall >= 0.08,
        }
        accepted = all(checks.values())
        result = {
            "trained_at": datetime.now().isoformat(), "league": code,
            "dataset_size": int(len(league_df)), "train_size": int(len(train_df)),
            "validation_size": int(len(valid_df)), "test_size": int(len(test_df)),
            "split": f"{','.join(map(str, train_years))}_to_{valid_year}_to_{test_year}",
            "strategy": "league-specialist-xgb-logistic-elo-v1",
            "accuracy": round(selected_metrics["accuracy"], 4),
            "baseline_accuracy": round(baseline_metrics["accuracy"], 4),
            "balanced_accuracy": round(selected_metrics["balanced_accuracy"], 4),
            "log_loss": round(selected_metrics["log_loss"], 4),
            "baseline_log_loss": round(baseline_metrics["log_loss"], 4),
            "brier_score": round(selected_metrics["brier_score"], 4),
            "baseline_brier_score": round(baseline_metrics["brier_score"], 4),
            "draw_recall": round(draw_recall, 4),
            "validation_metrics": {k: round(float(v), 4) for k, v in validation_metrics.items()},
            "test_metrics": {k: round(float(v), 4) for k, v in selected_metrics.items()},
            "baseline_metrics": {k: round(float(v), 4) for k, v in baseline_metrics.items()},
            "blend_weight_xgboost": round(float(blend_weight), 2),
            "blend_weight_elo": round(float(elo_weight), 2),
            "blend_weight_model": round(float(model_weight), 2),
            "calibration_temperature": round(float(temperature), 4),
            "class_bias": class_bias,
            "feature_names": feature_names,
            "data_quality": training_quality_report(league_df),
            "model_scope": {"competition": code, "seasons": TRAIN_SEASONS,
                            "warning": "仅在本联赛最新赛季滚动留出集通过门槛后启用"},
            "promotion": {"accepted": accepted, "checks": checks,
                          "decision": "ACCEPTED" if accepted else "REJECTED",
                          "reason": "league holdout passed" if accepted else "league holdout did not beat baseline"},
        }
        model_path = os.path.join(MODEL_DIR, f"league_{code.lower()}.joblib")
        report_path = os.path.join(MODEL_DIR, f"league_{code.lower()}.json")
        joblib.dump({"xgb": final_xgb, "logistic": final_logistic, "scaler": final_scaler,
                     "blend_weight_xgboost": blend_weight, "blend_weight_elo": elo_weight,
                     "blend_weight_model": model_weight, "calibration_temperature": temperature,
                     "class_bias": class_bias,
                     "feature_names": feature_names, "strategy": result["strategy"],
                     "leagueCode": code, "abstain_threshold": 0.0}, model_path)
        with open(report_path, "w", encoding="utf-8") as report_file:
            json.dump(result, report_file, ensure_ascii=False, indent=2)
        reports[code] = result
        print(f"[Specialist] {code}: accuracy={selected_metrics['accuracy']:.4f} vs ELO={baseline_metrics['accuracy']:.4f}, accepted={accepted}")
    return reports


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
    load_database_enrichment()
    load_understat_enrichment()

    all_records = []

    if TRAIN_DATA_SOURCE == "football-data":
        if not FOOTBALL_DATA_API_KEY:
            raise RuntimeError("Missing FOOTBALL_DATA_API_KEY. Please set it before training.")
        print(f"[Config] football-data competitions: {', '.join(FOOTBALL_DATA_CODES)}")
        for code in FOOTBALL_DATA_CODES:
            name = FOOTBALL_DATA_COMPETITIONS.get(code, code)
            competition_matches = []
            for season in TRAIN_SEASONS:
                print(f"\n[Fetch] football-data {name} {season}...")
                matches = fetch_football_data_matches(code, season)
                print(f"[Fetch] Got {len(matches)} matches")
                competition_matches.extend(matches)
            records = build_football_data_features(competition_matches, None, code)
            all_records.extend(records)
            print(f"[Fetch] Processed {len(records)} matches from {name} across {len(TRAIN_SEASONS)} seasons")
    elif TRAIN_DATA_SOURCE == "api-football":
        if not ALLOW_NON_POINT_IN_TIME_API_TRAINING:
            raise RuntimeError(
                "拒绝使用 API-Football 赛季累计统计训练：该分支包含非 point-in-time 特征，"
                "可能造成时间泄漏。请改用 TRAIN_DATA_SOURCE=football-data，或在完成历史快照后显式设置 "
                "ALLOW_NON_POINT_IN_TIME_API_TRAINING=true。"
            )
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

    # Every source must materialize the same schema. Missing enrichment is
    # explicit (neutral numeric value) and is reported in the training audit;
    # it can never reorder or silently omit a feature column.
    for record in all_records:
        for name in FEATURE_NAMES:
            record.setdefault(name, 0.0)
    df = pd.DataFrame(all_records)
    if "_match_date" in df.columns:
        df["_match_date"] = pd.to_datetime(df["_match_date"], errors="coerce")
        df = df.sort_values("_match_date", kind="stable")
    dedupe_columns = [column for column in ["_match_id", "_match_date"] if column in df.columns]
    if dedupe_columns:
        before = len(df)
        df = df.drop_duplicates(subset=dedupe_columns, keep="first")
        print(f"[Data] Removed {before - len(df)} duplicate records")
    print(f"\n[Data] Total records: {len(df)}")
    print(f"[Data] Class distribution:\n{df['label'].value_counts()}")

    train_xgboost(df)
    # Specialist artifacts are published independently from the global
    # candidate. Each league report carries its own holdout gate; the inference
    # service only routes to an accepted specialist and otherwise uses global.
    train_league_specialists(df)


if __name__ == "__main__":
    main()
