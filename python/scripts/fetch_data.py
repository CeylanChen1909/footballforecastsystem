# -*- coding: utf-8 -*-
"""
数据拉取脚本 - 从 API-Football 拉取历史比赛数据并保存为 JSON
用法:
    python fetch_data.py <API_KEY> <start_date> <end_date> <league_id> [output_path]
示例:
    python fetch_data.py 7294a6ff586cc5f3531171bc154ced85 2024-01-01 2025-03-24 39 data/fixtures.json
"""
import sys
import json
import os
import time
import requests
from datetime import datetime

BASE_DIR = os.path.dirname(os.path.abspath(__file__))
DATA_DIR = os.path.join(BASE_DIR, "data")
os.makedirs(DATA_DIR, exist_ok=True)

BASE_URL = "https://v3.football.api-sports.io"


def fetch_fixtures(api_key, league_id, season, date_from, date_to, max_pages=10):
    """按日期范围分页拉取比赛数据"""
    all_fixtures = []
    headers = {"x-apisports-key": api_key}

    # 按月分批拉取
    start = datetime.strptime(date_from, "%Y-%m-%d")
    end = datetime.strptime(date_to, "%Y-%m-%d")
    current = start

    while current <= end:
        month_end = min(current + __import__("datetime").timedelta(days=31), end)
        from_str = current.strftime("%Y-%m-%d")
        to_str = month_end.strftime("%Y-%m-%d")

        print(f"  拉取 {from_str} ~ {to_str} ...")

        page = 1
        while page <= max_pages:
            params = {
                "league": league_id,
                "season": season,
                "from": from_str,
                "to": to_str,
                "page": page,
            }
            try:
                resp = requests.get(f"{BASE_URL}/fixtures", headers=headers, params=params, timeout=30)
                data = resp.json()
                if data.get("errors") and any(data["errors"].values()):
                    print(f"    第{page}页出错: {data['errors']}")
                    break
                results = data.get("results", 0)
                fixtures = data.get("response", [])
                all_fixtures.extend(fixtures)
                print(f"    第{page}页: {results} 条 (累计 {len(all_fixtures)})")
                if results == 0 or len(fixtures) == 0:
                    break
                page += 1
                time.sleep(1)  # 避免超限
            except Exception as e:
                print(f"    请求异常: {e}")
                break

        current = month_end + __import__("datetime").timedelta(days=1)

    return all_fixtures


def main():
    if len(sys.argv) < 5:
        print("用法: python fetch_data.py <API_KEY> <start_date> <end_date> <league_id> [output_path]")
        print("示例: python fetch_data.py 7294a6ff586cc5f3531171bc154ced85 2024-01-01 2025-03-24 39 data/fixtures.json")
        sys.exit(1)

    api_key = sys.argv[1]
    date_from = sys.argv[2]
    date_to = sys.argv[3]
    league_id = sys.argv[4]
    output_path = sys.argv[5] if len(sys.argv) > 5 else os.path.join(DATA_DIR, "fixtures.json")

    # 从日期推断赛季
    year = int(date_from[:4])

    print(f"[数据拉取] 联赛ID={league_id}, 赛季={year}, {date_from} ~ {date_to}")
    fixtures = fetch_fixtures(api_key, league_id, year, date_from, date_to)
    print(f"[数据拉取] 共获取 {len(fixtures)} 条比赛记录")

    result = {"response": fixtures, "results": len(fixtures)}
    with open(output_path, "w", encoding="utf-8") as f:
        json.dump(result, f, ensure_ascii=False, indent=2)
    print(f"[数据拉取] 已保存至: {output_path}")


if __name__ == "__main__":
    main()
