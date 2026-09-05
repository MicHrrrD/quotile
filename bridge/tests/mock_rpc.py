"""Offline stdio fixture. It never invokes Codex or reads account credentials."""
import json
from pathlib import Path
import sys
import time

mode, events_path = sys.argv[1:]
initialized = False
notified = False


def emit(value):
    print(json.dumps(value), flush=True)


for line in sys.stdin:
    request = json.loads(line)
    with Path(events_path).open("a", encoding="utf-8") as events:
        events.write(json.dumps(request) + "\n")
    method = request.get("method")
    if method == "initialize":
        if mode == "initialize_error":
            emit({"id": request["id"], "error": {"message": "PRIVATE_UPSTREAM_DETAIL"}})
        else:
            initialized = True
            emit({"id": request["id"], "result": {"userAgent": "offline-fixture"}})
    elif method == "initialized":
        notified = True
    elif method == "account/rateLimits/read":
        assert initialized and notified
        if mode == "hang":
            time.sleep(60)
        elif mode == "exit":
            sys.exit(0)
        elif mode == "error":
            emit({"id": request["id"], "error": {"message": "PRIVATE_UPSTREAM_DETAIL"}})
        elif mode == "oversized":
            print("x" * (1024 * 1024 + 1), flush=True)
        elif mode == "malformed":
            print("{bad-json", flush=True)
        else:
            for _ in range(100):
                emit({"method": "account/rateLimits/updated", "params": {"ignored": True}})
            if mode == "callback":
                emit({"id": "callback", "method": "account/chatgptAuthTokens/refresh", "params": {}})
                response = json.loads(sys.stdin.readline())
                with Path(events_path).open("a", encoding="utf-8") as events:
                    events.write(json.dumps(response) + "\n")
                assert response.get("error", {}).get("code") == -32601
            emit({"id": 9999999, "result": {"notOurResponse": True}})
            emit({"id": request["id"], "result": {"rateLimitsByLimitId": {"codex": {
                "limitId": "codex", "planType": "pro",
                "primary": {"windowDurationMins": 300, "usedPercent": 25, "resetsAt": 2000000000},
                "secondary": {"windowDurationMins": 10080, "usedPercent": 70, "resetsAt": 2000600000}
            }}}})
    else:
        raise AssertionError("Unexpected method")
