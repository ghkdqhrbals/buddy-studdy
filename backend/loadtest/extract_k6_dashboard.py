#!/usr/bin/env python3
import argparse
import base64
import gzip
import json
import re
from pathlib import Path


DATA_PATTERN = re.compile(r'<script id="data"[^>]*>(.*?)</script>', re.DOTALL)


def aggregate_names(metric_type, aggregates):
    return aggregates.get(metric_type, ["value"])


def metric_values(names, snapshot, metric_name, metric_types, aggregates):
    if metric_name not in names:
        return {}
    values = snapshot[names.index(metric_name)]
    keys = aggregate_names(metric_types[metric_name], aggregates)
    return dict(zip(keys, values))


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--input", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()

    match = DATA_PATTERN.search(args.input.read_text())
    if not match:
        raise SystemExit(f"Embedded k6 dashboard data was not found: {args.input}")

    payload = gzip.decompress(base64.b64decode(match.group(1).strip())).decode()
    events = [json.loads(line) for line in payload.splitlines() if line.strip()]
    metric_types = {}
    aggregates = {}
    snapshots = []

    for event in events:
        if event.get("event") == "param":
            aggregates = event.get("data", {}).get("aggregates", {})
        elif event.get("event") == "metric":
            for name, metadata in event.get("data", {}).items():
                metric_types[name] = metadata.get("type", "gauge")
        elif event.get("event") == "snapshot":
            names = sorted(metric_types)
            snapshots.append((names, event.get("data", [])))

    output = []
    for index, (names, snapshot) in enumerate(snapshots):
        requests = metric_values(names, snapshot, "http_reqs", metric_types, aggregates)
        duration = metric_values(names, snapshot, "http_req_duration", metric_types, aggregates)
        failures = metric_values(names, snapshot, "http_req_failed", metric_types, aggregates)
        dropped = metric_values(names, snapshot, "dropped_iterations", metric_types, aggregates)
        vus = metric_values(names, snapshot, "vus", metric_types, aggregates)
        time = metric_values(names, snapshot, "time", metric_types, aggregates)
        output.append(
            {
                "timestampMs": time.get("value", 0.0),
                "elapsedSeconds": index,
                "rps": requests.get("rate", 0.0),
                "requests": requests.get("count", 0.0),
                "meanMs": duration.get("avg", 0.0),
                "p50Ms": duration.get("med", 0.0),
                "p90Ms": duration.get("p(90)", 0.0),
                "p95Ms": duration.get("p(95)", 0.0),
                "p99Ms": duration.get("p(99)", 0.0),
                "failureRate": failures.get("rate", 0.0),
                "dropped": dropped.get("count", 0.0),
                "vus": vus.get("value", 0.0),
            }
        )

    if not output:
        raise SystemExit(f"No k6 dashboard snapshots were found: {args.input}")
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(output, separators=(",", ":")) + "\n")


if __name__ == "__main__":
    main()
