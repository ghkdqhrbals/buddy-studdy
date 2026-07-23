#!/usr/bin/env python3
import argparse
import json
from pathlib import Path


def metric(data, name, key):
    value = data.get("metrics", {}).get(name, {}).get("values", {}).get(key, 0)
    return float(value or 0)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--results", type=Path, required=True)
    parser.add_argument("--runtime", required=True)
    parser.add_argument("--round", type=int, required=True)
    parser.add_argument("--scenario", required=True)
    parser.add_argument("--tested", required=True)
    args = parser.parse_args()

    tested = sorted({int(value) for value in args.tested.split(",")})
    saturation = None
    for target in tested:
        path = (
            args.results
            / "raw"
            / f"{args.runtime}-round{args.round}-{args.scenario}-rps{target}.json"
        )
        if not path.exists():
            continue
        data = json.loads(path.read_text())
        achieved = metric(data, "http_reqs", "rate")
        failed = max(
            metric(data, "http_req_failed", "rate"),
            metric(data, "response_validation_failed", "rate"),
        )
        dropped = metric(data, "dropped_iterations", "count")
        if achieved < target * 0.95 or failed > 0.01 or dropped > 0:
            saturation = target
            break

    if saturation is None:
        return
    lower = max(100, saturation - 400)
    candidates = range(lower, saturation + 101, 100)
    print(" ".join(str(value) for value in candidates if value not in tested))


if __name__ == "__main__":
    main()
