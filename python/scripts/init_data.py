# -*- coding: utf-8 -*-
"""
模拟数据生成脚本 - 用于快速体验系统功能
在没有真实 API 数据时，生成模拟比赛数据进行训练和测试
"""
import json
import os
import random
from datetime import datetime, timedelta

BASE_DIR = os.path.dirname(os.path.abspath(__file__))
DATA_DIR = os.path.join(BASE_DIR, "data")
os.makedirs(DATA_DIR, exist_ok=True)

# 模拟球队数据
TEAMS = [
    {"id": 33, "name": "Manchester United", "league_id": 39, "league_name": "Premier League"},
    {"id": 34, "name": "Newcastle United", "league_id": 39, "league_name": "Premier League"},
    {"id": 40, "name": "Tottenham Hotspur", "league_id": 39, "league_name": "Premier League"},
    {"id": 49, "name": "Chelsea", "league_id": 39, "league_name": "Premier League"},
    {"id": 50, "name": "Manchester City", "league_id": 39, "league_name": "Premier League"},
    {"id": 47, "name": "Liverpool", "league_id": 39, "league_name": "Premier League"},
    {"id": 66, "name": "Arsenal", "league_id": 39, "league_name": "Premier League"},
    {"id": 65, "name": "Aston Villa", "league_id": 39, "league_name": "Premier League"},
    {"id": 33, "name": "Real Madrid", "league_id": 140, "league_name": "La Liga"},
    {"id": 541, "name": "Barcelona", "league_id": 140, "league_name": "La Liga"},
    {"id": 79, "name": "Bayern Munich", "league_id": 78, "league_name": "Bundesliga"},
    {"id": 165, "name": "Borussia Dortmund", "league_id": 78, "league_name": "Bundesliga"},
    {"id": 85, "name": "Paris Saint-Germain", "league_id": 61, "league_name": "Ligue 1"},
    {"id": 115, "name": "Olympique Lyon", "league_id": 61, "league_name": "Ligue 1"},
    {"id": 108, "name": "Inter Milan", "league_id": 135, "league_name": "Serie A"},
    {"id": 109, "name": "AC Milan", "league_id": 135, "league_name": "Serie A"},
]

def generate_fixtures(start_date, end_date, league_id=39, fixtures_per_day=3):
    """生成模拟比赛数据"""
    fixtures = []
    current_date = datetime.strptime(start_date, "%Y-%m-%d")
    end = datetime.strptime(end_date, "%Y-%m-%d")
    
    teams_in_league = [t for t in TEAMS if t["league_id"] == league_id]
    if len(teams_in_league) < 2:
        teams_in_league = TEAMS[:8]
    
    fixture_id = 1
    
    while current_date <= end:
        # 每天生成几场比赛
        for _ in range(fixtures_per_day):
            if len(teams_in_league) < 2:
                continue
                
            home = random.choice(teams_in_league)
            away = random.choice([t for t in teams_in_league if t["id"] != home["id"]])
            
            # 模拟比赛时间 (北京时间 19:30 - 23:00)
            match_hour = random.randint(19, 23)
            match_minute = random.choice([0, 30, 45])
            match_time = current_date.replace(hour=match_hour, minute=match_minute)
            
            # 模拟比分 (主场略微优势)
            home_elo = random.uniform(1400, 1700)
            away_elo = random.uniform(1400, 1700)
            home_advantage = 50  # 主场优势
            
            # 基于ELO计算进球期望
            home_expected = max(0.5, 1.5 + (home_elo - 1500) / 400 + home_advantage / 100)
            away_expected = max(0.5, 1.5 + (away_elo - 1500) / 400)
            
            home_goals = max(0, min(5, int(round(random.gauss(home_expected, 0.8)))))
            away_goals = max(0, min(5, int(round(random.gauss(away_expected, 0.7)))))
            
            # 确保有结果的概率
            if random.random() > 0.15:
                if home_goals == away_goals:
                    home_goals = random.choice([home_goals + 1, home_goals - 1, home_goals])
                    home_goals = max(0, home_goals)
            
            fixture = {
                "fixture": {
                    "id": fixture_id,
                    "referee": random.choice(["Michael Oliver", "Anthony Taylor", "Martin Atkinson", "Howard Webb"]),
                    "timezone": "Asia/Shanghai",
                    "date": match_time.strftime("%Y-%m-%dT%H:%M:%S+08:00"),
                    "timestamp": int(match_time.timestamp()),
                    "venue": {
                        "id": random.randint(1000, 9999),
                        "name": f"{home['name']} Stadium",
                        "city": random.choice(["Manchester", "London", "Liverpool", "Birmingham", "Newcastle"])
                    },
                    "status": {
                        "long": "Match Finished",
                        "short": "FT",
                        "elapsed": 90
                    }
                },
                "league": {
                    "id": league_id,
                    "name": teams_in_league[0]["league_name"],
                    "country": "England",
                    "logo": f"https://media.api-sports.io/football/leagues/{league_id}.png",
                    "flag": "https://media.api-sports.io/flags/gb.svg",
                    "season": current_date.year,
                    "round": f"Regular Season - {current_date.isocalendar()[1]}"
                },
                "teams": {
                    "home": {
                        "id": home["id"],
                        "name": home["name"],
                        "logo": f"https://media.api-sports.io/football/teams/{home['id']}.png",
                        "winner": home_goals > away_goals
                    },
                    "away": {
                        "id": away["id"],
                        "name": away["name"],
                        "logo": f"https://media.api-sports.io/football/teams/{away['id']}.png",
                        "winner": away_goals > home_goals
                    }
                },
                "goals": {
                    "home": home_goals,
                    "away": away_goals
                },
                "score": {
                    "halftime": {
                        "home": max(0, home_goals + random.randint(-1, 1)),
                        "away": max(0, away_goals + random.randint(-1, 1))
                    },
                    "fulltime": {
                        "home": home_goals,
                        "away": away_goals
                    },
                    "extratime": {
                        "home": None,
                        "away": None
                    },
                    "penalty": {
                        "home": None,
                        "away": None
                    }
                }
            }
            
            fixtures.append(fixture)
            fixture_id += 1
        
        current_date += timedelta(days=1)
    
    return fixtures


def generate_teams(league_id=39):
    """生成模拟球队数据"""
    teams = []
    league_teams = [t for t in TEAMS if t["league_id"] == league_id]
    
    for team in league_teams:
        teams.append({
            "team": {
                "id": team["id"],
                "name": team["name"],
                "logo": f"https://media.api-sports.io/football/teams/{team['id']}.png",
                "country": "England",
                "founded": random.randint(1880, 2000),
                "venue": {
                    "name": f"{team['name']} Stadium",
                    "capacity": random.choice([20000, 30000, 40000, 50000, 60000]),
                    "city": "Manchester"
                }
            },
            "venue": {
                "id": random.randint(1000, 9999),
                "name": f"{team['name']} Stadium",
                "address": "Stadium Address",
                "city": "Manchester",
                "capacity": random.randint(20000, 60000),
                "surface": "grass",
                "image": None
            }
        })
    
    return teams


def main():
    import argparse
    parser = argparse.ArgumentParser(description='生成模拟足球数据')
    parser.add_argument('--start', default='2024-01-01', help='开始日期')
    parser.add_argument('--end', default='2025-03-24', help='结束日期')
    parser.add_argument('--league', type=int, default=39, help='联赛ID')
    parser.add_argument('--per-day', type=int, default=3, help='每天比赛数')
    parser.add_argument('--output', default=None, help='输出文件路径')
    parser.add_argument('--teams', action='store_true', help='只生成球队数据')
    
    args = parser.parse_args()
    
    if args.teams:
        teams = generate_teams(args.league)
        output_path = args.output or os.path.join(DATA_DIR, "teams.json")
        result = {"response": teams, "results": len(teams)}
    else:
        fixtures = generate_fixtures(args.start, args.end, args.league, args.per_day)
        output_path = args.output or os.path.join(DATA_DIR, "fixtures.json")
        result = {"response": fixtures, "results": len(fixtures)}
    
    with open(output_path, "w", encoding="utf-8") as f:
        json.dump(result, f, ensure_ascii=False, indent=2)
    
    print(f"[数据生成] 已生成 {result['results']} 条记录")
    print(f"[数据生成] 已保存至: {output_path}")


if __name__ == "__main__":
    main()
