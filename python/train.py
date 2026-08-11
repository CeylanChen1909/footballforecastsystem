# -*- coding: utf-8 -*-
"""
足球比赛结果预测 - XGBoost 训练脚本
从 API-Football 拉取历史比赛数据，生成训练集，训练模型并保存。
"""
import json
import os
import sys
from datetime import datetime, timedelta

import pandas as pd
import xgboost as xgb
from sklearn.model_selection import train_test_split
from sklearn.preprocessing import LabelEncoder
import joblib

BASE_DIR = os.path.dirname(os.path.abspath(__file__))
DATA_DIR = os.path.join(BASE_DIR, "data")
MODEL_DIR = os.path.join(BASE_DIR, "models")
os.makedirs(DATA_DIR, exist_ok=True)
os.makedirs(MODEL_DIR, exist_ok=True)

# 特征说明：
# 1. home_team_id / away_team_id          - 球队ID（已编码）
# 2. home_elo / away_elo                  - ELO 评分
# 3. home_win_rate / away_win_rate        - 近N场胜率
# 4. home_avg_goals / away_avg_goals      - 近N场平均进球
# 5. home_avg_loss / away_avg_loss        - 近N场平均失球
# 6. home_cards / away_cards              - 近N场平均黄红牌
# 7. home_days_rest / away_days_rest       - 休息天数
# 8. h2h_home_wins / h2h_draws / h2h_away - 往绩（主队胜/平/负次数）
# 标签: 0=主队胜(HOME_WIN), 1=平局(DRAW), 2=客队胜(AWAY_WIN)


class FootballDataProcessor:
    def __init__(self):
        self.team_encoder = LabelEncoder()
        self.fitted = False

    def load_from_json(self, path):
        with open(path, "r", encoding="utf-8") as f:
            raw = json.load(f)
        return raw

    def build_features(self, fixtures):
        """从 API-Football fixtures 构建特征 DataFrame"""
        rows = []
        for fixture in fixtures:
            f = fixture.get("fixture", {})
            league = fixture.get("league", {})
            teams = fixture.get("teams", {})
            goals = fixture.get("goals", {})
            score = fixture.get("score", {})

            fixture_id = f.get("id")
            timestamp = f.get("timestamp")
            if not timestamp:
                continue

            home_id = teams.get("home", {}).get("id")
            away_id = teams.get("away", {}).get("id")
            if not home_id or not away_id:
                continue

            # 标签（如果比赛已结束才有 score）
            home_goals = goals.get("home")
            away_goals = goals.get("away")
            if home_goals is None or away_goals is None:
                continue

            if home_goals > away_goals:
                label = 0
            elif home_goals < away_goals:
                label = 2
            else:
                label = 1

            rows.append({
                "fixture_id": fixture_id,
                "timestamp": timestamp,
                "league_id": league.get("id"),
                "home_team_id": home_id,
                "away_team_id": away_id,
                "home_goals": home_goals,
                "away_goals": away_goals,
                "label": label,
            })

        if not rows:
            return pd.DataFrame()

        df = pd.DataFrame(rows)
        df["datetime"] = pd.to_datetime(df["timestamp"], unit="s")

        # --- ELO 初始化（默认 1500） ---
        elo = {}
        def get_elo(team_id):
            if team_id not in elo:
                elo[team_id] = 1500.0
            return elo[team_id]

        def update_elo(home_id, away_id, label, k=32):
            home_elo = get_elo(home_id)
            away_elo = get_elo(away_id)
            # 期望胜率
            e_home = 1 / (1 + 10 ** ((away_elo - home_elo) / 400))
            e_away = 1 / (1 + 10 ** ((home_elo - away_elo) / 400))
            if label == 0:
                actual_home, actual_away = 1, 0
            elif label == 2:
                actual_home, actual_away = 0, 1
            else:
                actual_home, actual_away = 0.5, 0.5

            elo[home_id] = home_elo + k * (actual_home - e_home)
            elo[away_id] = away_elo + k * (actual_away - e_away)

        # --- 计算滚动特征（近10场） ---
        feature_rows = []
        df = df.sort_values("datetime").reset_index(drop=True)

        # 每个队的近10场统计
        team_stats = {}  # team_id -> deque of {goals, loss, cards, win, draw, lose}

        for _, row in df.iterrows():
            hid = row["home_team_id"]
            aid = row["away_team_id"]
            label = row["label"]
            dt = row["datetime"]

            # 计算特征（取近10场）
            def calc_stats(team_id):
                if team_id not in team_stats:
                    return {
                        "elo": 1500,
                        "win_rate": 0.5,
                        "avg_goals": 1.5,
                        "avg_loss": 1.5,
                        "avg_cards": 1.0,
                        "days_rest": 7,
                        "h2h_home_wins": 0,
                        "h2h_draws": 0,
                        "h2h_away_wins": 0,
                    }
                stats = list(team_stats[team_id])[-10:]
                n = len(stats)
                wins = sum(1 for s in stats if s["result"] == 0)
                draws = sum(1 for s in stats if s["result"] == 1)
                losses = sum(1 for s in stats if s["result"] == 2)
                total_goals = sum(s["goals"] for s in stats)
                total_loss = sum(s["loss"] for s in stats)
                total_cards = sum(s["cards"] for s in stats)
                days_rest = 7
                if len(stats) > 0:
                    days_rest = max(1, (dt - stats[-1]["dt"]).days)
                h2h = [s for s in team_stats.get(team_id, []) if s.get("h2h")]
                h2h_hw = sum(1 for s in h2h if s["result"] == 0)
                h2h_d = sum(1 for s in h2h if s["result"] == 1)
                h2h_aw = sum(1 for s in h2h if s["result"] == 2)
                return {
                    "elo": get_elo(team_id),
                    "win_rate": (wins + draws * 0.5) / n,
                    "avg_goals": total_goals / n,
                    "avg_loss": total_loss / n,
                    "avg_cards": total_cards / n,
                    "days_rest": days_rest,
                    "h2h_home_wins": h2h_hw,
                    "h2h_draws": h2h_d,
                    "h2h_away_wins": h2h_aw,
                }

            home_stats = calc_stats(hid)
            away_stats = calc_stats(aid)

            feature_rows.append({
                "home_team_id": hid,
                "away_team_id": aid,
                "home_elo": home_stats["elo"],
                "away_elo": away_stats["elo"],
                "home_win_rate": home_stats["win_rate"],
                "away_win_rate": away_stats["win_rate"],
                "home_avg_goals": home_stats["avg_goals"],
                "away_avg_goals": away_stats["avg_goals"],
                "home_avg_loss": home_stats["avg_loss"],
                "away_avg_loss": away_stats["avg_loss"],
                "home_avg_cards": home_stats["avg_cards"],
                "away_avg_cards": away_stats["avg_cards"],
                "home_days_rest": home_stats["days_rest"],
                "away_days_rest": away_stats["days_rest"],
                "h2h_home_wins": home_stats["h2h_home_wins"],
                "h2h_draws": home_stats["h2h_draws"],
                "h2h_away_wins": home_stats["h2h_away_wins"],
                "label": label,
                "fixture_id": row["fixture_id"],
            })

            # 更新统计
            if hid not in team_stats:
                team_stats[hid] = []
            if aid not in team_stats:
                team_stats[aid] = []

            # 主队统计（视角）
            home_result = label  # 0=主胜, 1=平, 2=主负
            home_goals_f = row["home_goals"]
            away_goals_f = row["away_goals"]

            team_stats[hid].append({
                "result": label,
                "goals": home_goals_f,
                "loss": away_goals_f,
                "cards": 1.5,
                "dt": dt,
                "h2h": True,
            })
            team_stats[aid].append({
                "result": 2 - label,  # 客队视角翻转
                "goals": away_goals_f,
                "loss": home_goals_f,
                "cards": 1.5,
                "dt": dt,
                "h2h": True,
            })

            update_elo(hid, aid, label)

        return pd.DataFrame(feature_rows)

    def fit_transform_teams(self, df):
        all_teams = pd.concat([df["home_team_id"], df["away_team_id"]]).unique()
        self.team_encoder.fit(all_teams)
        self.fitted = True
        return self

    def transform_teams(self, df):
        if not self.fitted:
            self.fit_transform_teams(df)
        df = df.copy()
        df["home_team_id"] = self.team_encoder.transform(df["home_team_id"])
        df["away_team_id"] = self.team_encoder.transform(df["away_team_id"])
        return df


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


def train(input_json_path, output_model_path, output_encoder_path):
    print(f"[训练] 读取数据: {input_json_path}")
    with open(input_json_path, "r", encoding="utf-8") as f:
        raw = json.load(f)

    # 支持两种格式：直接是 fixture 列表，或包装在 {"response": [...]} 里
    if isinstance(raw, dict) and "response" in raw:
        fixtures = raw["response"]
    elif isinstance(raw, list):
        fixtures = raw
    else:
        raise ValueError("未知 JSON 格式，需要 fixture 列表或 {response: [...]}")

    print(f"[训练] 共 {len(fixtures)} 条比赛记录")

    processor = FootballDataProcessor()
    df = processor.build_features(fixtures)
    if df.empty:
        print("[训练] 警告: 没有可用的已完成比赛数据，请确认 JSON 中包含 goals 字段")
        # 生成模拟数据进行演示
        import numpy as np
        np.random.seed(42)
        n = 500
        df = pd.DataFrame({
            "home_team_id": np.random.randint(1, 50, n),
            "away_team_id": np.random.randint(1, 50, n),
            "home_elo": np.random.uniform(1400, 1700, n),
            "away_elo": np.random.uniform(1400, 1700, n),
            "home_win_rate": np.random.uniform(0.2, 0.8, n),
            "away_win_rate": np.random.uniform(0.2, 0.8, n),
            "home_avg_goals": np.random.uniform(0.5, 2.5, n),
            "away_avg_goals": np.random.uniform(0.5, 2.5, n),
            "home_avg_loss": np.random.uniform(0.5, 2.5, n),
            "away_avg_loss": np.random.uniform(0.5, 2.5, n),
            "home_avg_cards": np.random.uniform(0.5, 3.0, n),
            "away_avg_cards": np.random.uniform(0.5, 3.0, n),
            "home_days_rest": np.random.randint(1, 14, n),
            "away_days_rest": np.random.randint(1, 14, n),
            "h2h_home_wins": np.random.randint(0, 10, n),
            "h2h_draws": np.random.randint(0, 10, n),
            "h2h_away_wins": np.random.randint(0, 10, n),
            "label": np.random.choice([0, 1, 2], n, p=[0.45, 0.25, 0.30]),
        })
        print("[训练] 使用模拟数据进行演示训练（正式环境请提供真实历史比赛数据）")

    print(f"[训练] 特征构建完成，共 {len(df)} 条记录")

    # 编码球队 ID
    df = processor.fit_transform_teams(df).transform_teams(df)
    X = df[FEATURE_COLS].values
    y = df["label"].values

    # 划分训练/验证集
    X_train, X_val, y_train, y_val = train_test_split(X, y, test_size=0.2, random_state=42)

    print(f"[训练] 训练集: {len(X_train)}, 验证集: {len(X_val)}")

    # 训练 XGBoost（多分类）
    model = xgb.XGBClassifier(
        n_estimators=200,
        max_depth=5,
        learning_rate=0.1,
        objective="multi:softprob",
        num_class=3,
        eval_metric="mlogloss",
        early_stopping_rounds=20,
        random_state=42,
        use_label_encoder=False,
    )
    model.fit(
        X_train, y_train,
        eval_set=[(X_val, y_val)],
        verbose=10,
    )

    # 评估
    from sklearn.metrics import accuracy_score, classification_report
    y_pred = model.predict(X_val)
    acc = accuracy_score(y_val, y_pred)
    print(f"[训练] 验证集准确率: {acc:.4f}")
    print(classification_report(y_val, y_pred, target_names=["HOME_WIN", "DRAW", "AWAY_WIN"]))

    # 保存模型 + 编码器
    model.save_model(output_model_path)
    joblib.dump(processor.team_encoder, output_encoder_path)
    print(f"[训练] 模型已保存: {output_model_path}")
    print(f"[训练] 编码器已保存: {output_encoder_path}")

    # 保存特征重要性
    importance = dict(zip(FEATURE_COLS, model.feature_importances_.tolist()))
    imp_path = os.path.join(MODEL_DIR, "feature_importance.json")
    with open(imp_path, "w", encoding="utf-8") as f:
        json.dump(importance, f, ensure_ascii=False, indent=2)
    print(f"[训练] 特征重要性已保存: {imp_path}")


if __name__ == "__main__":
    input_path = sys.argv[1] if len(sys.argv) > 1 else os.path.join(DATA_DIR, "fixtures.json")
    model_path = sys.argv[2] if len(sys.argv) > 2 else os.path.join(MODEL_DIR, "xgboost_model.json")
    encoder_path = sys.argv[3] if len(sys.argv) > 3 else os.path.join(MODEL_DIR, "team_encoder.pkl")
    train(input_path, model_path, encoder_path)
