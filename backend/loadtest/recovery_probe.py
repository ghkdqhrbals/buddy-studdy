#!/usr/bin/env python3
import argparse
import json
import statistics
import time
import urllib.error
import urllib.request
from datetime import datetime, timezone
from pathlib import Path


def percentile(values, quantile):
    if not values:
        return None
    ordered = sorted(values)
    position = (len(ordered) - 1) * quantile
    lower = int(position)
    upper = min(lower + 1, len(ordered) - 1)
    fraction = position - lower
    return ordered[lower] + (ordered[upper] - ordered[lower]) * fraction


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--url", required=True)
    parser.add_argument("--duration-seconds", type=int, required=True)
    parser.add_argument("--interval-seconds", type=float, default=1.0)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()

    started_at = datetime.now(timezone.utc)
    deadline = time.monotonic() + args.duration_seconds
    samples = []
    while time.monotonic() < deadline:
        sample_started = time.monotonic()
        status = None
        healthy = False
        try:
            with urllib.request.urlopen(args.url, timeout=2) as response:
                status = response.status
                body = json.load(response)
                healthy = status == 200 and body.get("ok") is True
        except (OSError, ValueError, urllib.error.HTTPError):
            pass
        elapsed_ms = (time.monotonic() - sample_started) * 1000
        samples.append(
            {
                "timestamp": datetime.now(timezone.utc).isoformat(),
                "status": status,
                "healthy": healthy,
                "durationMs": elapsed_ms,
            }
        )
        delay = args.interval_seconds - (time.monotonic() - sample_started)
        if delay > 0:
            time.sleep(min(delay, max(0, deadline - time.monotonic())))

    latencies = [sample["durationMs"] for sample in samples if sample["healthy"]]
    first_healthy = next(
        (
            (
                datetime.fromisoformat(sample["timestamp"]) - started_at
            ).total_seconds()
            * 1000
            for sample in samples
            if sample["healthy"]
        ),
        None,
    )
    result = {
        "schemaVersion": 1,
        "startedAt": started_at.isoformat(),
        "durationSeconds": args.duration_seconds,
        "summary": {
            "samples": len(samples),
            "healthySamples": sum(1 for sample in samples if sample["healthy"]),
            "failedSamples": sum(1 for sample in samples if not sample["healthy"]),
            "firstHealthyMs": first_healthy,
            "meanMs": statistics.mean(latencies) if latencies else None,
            "p95Ms": percentile(latencies, 0.95),
        },
        "samples": samples,
    }
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result, separators=(",", ":")) + "\n")


if __name__ == "__main__":
    main()
