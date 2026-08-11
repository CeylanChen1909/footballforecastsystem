# -*- coding: utf-8 -*-
"""
数据预处理脚本 - 特征工程
从 API-Football 原始数据中提取特征，生成训练数据
"""
import json
import os
import sys
import argparse
from datetime import datetime, timedelta
from collections import defaultdict

BASE_DIR = os.path.dirname(os.path.abspath(__file__))
DATA_DIR = os.path.join(BASE_DIR, "data")
MODEL_DIR = os.path.join(BASE_DIR, "models")


class FeatureExtractor:
    """特征提取器"""
    
    def __init__(self, elo_k=32, default_elo=1500):
        self.elo_k = elo_k
        self.default_elo = default_elo
        self.elo_ratings = defaultdict(lambda: default_elo)
        self.team_stats = defaultdict(list)  # 存储每个队的近期统计
        self.h2h_records = defaultdict(list)  # 存储交锋记录
    
    def reset(self):
        """重置所有状态"""
        self.elo_ratings = defaultdict(lambda: self.default_elo)
        self.team_stats = defaultdict(list)
        self.h2h_records = defaultdict(list)
    
    def update_elo(self, home_id, away_id, label):
        """更新 ELO 评分"""
        home_elo = self.elo_ratings[home_id]
        away_elo = self.elo_ratings[away_id]
        
        # 计算期望胜率
        e_home = 1 / (1 + 10 ** ((away_elo - home_elo) / 400))
        e_away = 1 - e_home
        
        if label == 0:  # 主队胜
            actual_home, actual_away = 1, 0
        elif label == 2:  # 客队胜
            actual_home, actual_away = 0, 1
        else:  # 平局
            actual_home, actual_away = 0.5, 0.5
        
        # 更新 ELO
        self.elo_ratings[home_id] = home_elo + self.elo_k * (actual_home - e_home)
        self.elo_ratings[away_id] = away_elo + self.elo_k * (actual_away - e_away)
    
    def update_team_stats(self, team_id, match_data):
        """更新球队统计"""
        self.team_stats[team_id].append(match_data)
        # 只保留最近20场
        if len(self.team_stats[team_id]) > 20:
            self.team_stats[team_id] = self.team_stats[team_id][-20:]
    
    def update_h2h(self, home_id, away_id, label):
        """更新交锋记录"""
        key = tuple(sorted([home_id, away_id]))
        self.h2h_records[key].append(label)
        if len(self.h2h_records[key]) > 10:
            self.h2h_records[key] = self.h2h_records[key][-10:]
    
    def get_team_features(self, team_id, is_home=True, match_date=None):
        """获取球队特征"""
        stats = self.team_stats.get(team_id, [])
        if not stats:
            return {
                "elo": self.default_elo,
                "win_rate": 0.5,
                "avg_goals": 1.5,
                "avg_loss": 1.5,
                "avg_cards": 2.0,
                "days_rest": 7,
                "home_win_rate": 0.5,
                "away_win_rate": 0.5,
            }
        
        # 取最近10场
        recent = stats[-10:]
        n = len(recent)
        
        wins = sum(1 for s in recent if s["result"] == 0)
        draws = sum(1 for s in recent if s["result"] == 1)
        losses = sum(1 for s in recent if s["result"] == 2)
        
        total_goals = sum(s["goals"] for s in recent)
        total_loss = sum(s["loss"] for s in recent)
        total_cards = sum(s["cards"] for s in recent)
        
        # 主场/客场胜率
        home_matches = [s for s in recent if s["is_home"]]
        away_matches = [s for s in recent if not s["is_home"]]
        
        home_wins = sum(1 for s in home_matches if s["result"] == 0)
        away_wins = sum(1 for s in away_matches if s["result"] == 0)
        
        # 休息天数
        days_rest = 7
        if len(stats) > 1:
            last_match_date = stats[-2].get("date", match_date)
            if last_match_date and match_date:
                try:
                    days_rest = (match_date - last_match_date).days
                    days_rest = max(1, min(14, days_rest))
                except:
                    days_rest = 7
        
        return {
            "elo": self.elo_ratings.get(team_id, self.default_elo),
            "win_rate": (wins + draws * 0.5) / n if n > 0 else 0.5,
            "avg_goals": total_goals / n if n > 0 else 1.5,
            "avg_loss": total_loss / n if n > 0 else 1.5,
            "avg_cards": total_cards / n if n > 0 else 2.0,
            "days_rest": days_rest,
            "home_win_rate": home_wins / len(home_matches) if home_matches else 0.5,
            "away_win_rate": away_wins / len(away_matches) if away_matches else 0.5,
        }
    
    def get_h2h_features(self, home_id, away_id):
        """获取交锋特征"""
        key = tuple(sorted([home_id, away_id]))
        records = self.h2h_records.get(key, [])
        
        if not records:
            return {"h2h_home_wins": 0, "h2h_draws": 0, "h2h_away_wins": 0}
        
        home_wins = sum(1 for r in records if r == 0)
        draws = sum(1 for r in records if r == 1)
        away_wins = sum(1 for r in records if r == 2)
        
        return {
            "h2h_home_wins": home_wins,
            "h2h_draws": draws,
            "h2h_away_wins": away_wins,
        }
    
    def process_fixtures(self, fixtures, look_back=10):
        """处理比赛数据，生成特征"""
        # 按时间排序
        sorted_fixtures = sorted(fixtures, key=lambda x: x.get("fixture", {}).get("timestamp", 0))
        
        feature_rows = []
        
        for fixture in sorted_fixtures:
            f = fixture.get("fixture", {})
            teams = fixture.get("teams", {})
            goals = fixture.get("goals", {})
            
            home_id = teams.get("home", {}).get("id")
            away_id = teams.get("away", {}).get("id")
            home_goals = goals.get("home")
            away_goals = goals.get("away")
            
            if not home_id or not away_id:
                continue
            
            timestamp = f.get("timestamp", 0)
            match_date = datetime.fromtimestamp(timestamp) if timestamp else None
            
            # 获取特征（在结果出来之前的数据）
            home_feat = self.get_team_features(home_id, is_home=True, match_date=match_date)
            away_feat = self.get_team_features(away_id, is_home=False, match_date=match_date)
            h2h_feat = self.get_h2h_features(home_id, away_id)
            
            # 记录结果标签（如果有比分）
            label = None
            if home_goals is not None and away_goals is not None:
                if home_goals > away_goals:
                    label = 0
                elif home_goals < away_goals:
                    label = 2
                else:
                    label = 1
            
            row = {
                "fixture_id": f.get("id"),
                "timestamp": timestamp,
                "home_team_id": home_id,
                "away_team_id": away_id,
                "home_elo": home_feat["elo"],
                "away_elo": away_feat["elo"],
                "home_win_rate": home_feat["win_rate"],
                "away_win_rate": away_feat["win_rate"],
                "home_avg_goals": home_feat["avg_goals"],
                "away_avg_goals": away_feat["avg_goals"],
                "home_avg_loss": home_feat["avg_loss"],
                "away_avg_loss": away_feat["avg_loss"],
                "home_avg_cards": home_feat["avg_cards"],
                "away_avg_cards": away_feat["avg_cards"],
                "home_days_rest": home_feat["days_rest"],
                "away_days_rest": away_feat["days_rest"],
                "home_home_win_rate": home_feat["home_win_rate"],
                "away_away_win_rate": away_feat["away_win_rate"],
                "h2h_home_wins": h2h_feat["h2h_home_wins"],
                "h2h_draws": h2h_feat["h2h_draws"],
                "h2h_away_wins": h2h_feat["h2h_away_wins"],
            }
            
            if label is not None:
                row["label"] = label
                feature_rows.append(row)
                
                # 更新状态
                self.update_elo(home_id, away_id, label)
                self.update_team_stats(home_id, {
                    "result": label,
                    "goals": home_goals,
                    "loss": away_goals,
                    "cards": 2.0,
                    "is_home": True,
                    "date": match_date
                })
                self.update_team_stats(away_id, {
                    "result": 2 - label,
                    "goals": away_goals,
                    "loss": home_goals,
                    "cards": 2.0,
                    "is_home": False,
                    "date": match_date
                })
                self.update_h2h(home_id, away_id, label)
            else:
                # 未来比赛，没有结果
                row["label"] = -1
                feature_rows.append(row)
        
        return feature_rows


def main():
    parser = argparse.ArgumentParser(description='特征工程 - 数据预处理')
    parser.add_argument('input', help='输入 JSON 文件路径')
    parser.add_argument('output', help='输出特征文件路径')
    parser.add_argument('--elo-k', type=float, default=32, help='ELO K因子')
    parser.add_argument('--default-elo', type=float, default=1500, help='默认ELO')
    
    args = parser.parse_args()
    
    print(f"[特征工程] 读取数据: {args.input}")
    with open(args.input, "r", encoding="utf-8") as f:
        raw = json.load(f)
    
    fixtures = raw.get("response", []) if isinstance(raw, dict) else raw
    print(f"[特征工程] 共 {len(fixtures)} 条比赛记录")
    
    extractor = FeatureExtractor(elo_k=args.elo_k, default_elo=args.default_elo)
    features = extractor.process_fixtures(fixtures)
    
    print(f"[特征工程] 生成 {len(features)} 条特征记录")
    print(f"[特征工程] ELO 评分覆盖 {len(extractor.elo_ratings)} 支球队")
    
    # 分离训练数据和预测数据
    train_data = [f for f in features if f.get("label", -1) >= 0]
    pred_data = [f for f in features if f.get("label", -1) < 0]
    
    print(f"[特征工程] 训练数据: {len(train_data)} 条")
    print(f"[特征工程] 待预测数据: {len(pred_data)} 条")
    
    result = {
        "features": features,
        "train_data": train_data,
        "pred_data": pred_data,
        "elo_ratings": dict(extractor.elo_ratings),
        "stats": {
            "total": len(features),
            "train": len(train_data),
            "predict": len(pred_data),
            "teams": len(extractor.elo_ratings)
        }
    }
    
    with open(args.output, "w", encoding="utf-8") as f:
        json.dump(result, f, ensure_ascii=False, indent=2)
    
    print(f"[特征工程] 已保存至: {args.output}")


if __name__ == "__main__":
    main()
