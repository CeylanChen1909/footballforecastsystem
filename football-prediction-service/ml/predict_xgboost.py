import json
import sys

if __name__ == "__main__":
    _ = json.loads(sys.argv[1]) if len(sys.argv) > 1 else {}
    print(json.dumps({
        "resultLabel": "HOME_WIN",
        "homeWinProb": 0.47,
        "drawProb": 0.26,
        "awayWinProb": 0.27,
        "explanation": "xgboost placeholder script"
    }))
