#!/usr/bin/env python3
import argparse
import json
import math
import re
from datetime import datetime
from pathlib import Path


K6_PATTERN = re.compile(
    r"^(mvc|webflux)-round(\d+)-(.+)-rps(\d+)\.json$"
)
NGRINDER_PATTERN = re.compile(
    r"^ngrinder-(mvc|webflux)-round(\d+)-(.+)-vu(\d+)\.json$"
)


def percentile(values, quantile):
    values = sorted(float(value) for value in values if isinstance(value, (int, float)))
    if not values:
        return None
    position = (len(values) - 1) * quantile
    lower = math.floor(position)
    upper = math.ceil(position)
    if lower == upper:
        return values[lower]
    return values[lower] + (values[upper] - values[lower]) * (position - lower)


def maximum(values):
    values = [float(value) for value in values if isinstance(value, (int, float))]
    return max(values) if values else None


def metric(data, name, key):
    value = data.get("metrics", {}).get(name, {}).get("values", {}).get(key)
    return float(value) if isinstance(value, (int, float)) else None


def jsonl(path):
    rows = []
    if not path.exists():
        return rows
    for line in path.read_text().splitlines():
        try:
            rows.append(json.loads(line))
        except ValueError:
            continue
    return rows


def recovery_summary(path):
    if not path.exists():
        return {
            "measured": False,
            "fullyHealthy": None,
            "failedSamples": None,
            "firstHealthyMs": None,
            "p95Ms": None,
        }
    data = json.loads(path.read_text())
    summary = data.get("summary", {})
    return {
        "measured": True,
        "fullyHealthy": (summary.get("failedSamples") or 0) == 0,
        "failedSamples": summary.get("failedSamples"),
        "firstHealthyMs": summary.get("firstHealthyMs"),
        "p95Ms": summary.get("p95Ms"),
    }


def nested_values(rows, *keys):
    values = []
    for row in rows:
        current = row
        for key in keys:
            if not isinstance(current, dict):
                current = None
                break
            current = current.get(key)
        if isinstance(current, (int, float)):
            values.append(float(current))
    return values


def counter_delta(values):
    return max(0, values[-1] - values[0]) if len(values) > 1 else 0


def elapsed_seconds(rows):
    if len(rows) < 2:
        return None
    try:
        first = datetime.fromisoformat(rows[0]["timestamp"])
        last = datetime.fromisoformat(rows[-1]["timestamp"])
    except (KeyError, TypeError, ValueError):
        return None
    elapsed = (last - first).total_seconds()
    return elapsed if elapsed > 0 else None


def server_resources(rows):
    app_cpu = nested_values(rows, "process", "cpu_percent")
    app_rss = nested_values(rows, "process", "rss_bytes")
    app_threads = nested_values(rows, "process", "os_threads")
    actuator = lambda name: nested_values(rows, "actuator", name)
    postgres = lambda name: nested_values(rows, "postgres", name)
    redis = lambda name: nested_values(rows, "redis", name)
    pg_cpu = nested_values(
        rows, "containers", "buddystudy-loadtest-postgres", "cpu_percent"
    )
    pg_memory = nested_values(
        rows, "containers", "buddystudy-loadtest-postgres", "memory_bytes"
    )
    redis_cpu = nested_values(
        rows, "containers", "buddystudy-loadtest-redis", "cpu_percent"
    )
    redis_memory = nested_values(
        rows, "containers", "buddystudy-loadtest-redis", "memory_bytes"
    )
    block_hits = postgres("blks_hit")
    block_reads = postgres("blks_read")
    hit_delta = counter_delta(block_hits)
    read_delta = counter_delta(block_reads)
    allocated = actuator("jvm.gc.allocated.count")
    promoted = actuator("jvm.gc.promoted.count")
    gc_pause_count = actuator("jvm.gc.pause.count")
    gc_pause_seconds = actuator("jvm.gc.pause.total_time")
    app_cpu_p95 = percentile(app_cpu, 0.95)
    return {
        "samples": len(rows),
        "appCpuP95": app_cpu_p95,
        "appCpuCoresP95": app_cpu_p95 / 100 if app_cpu_p95 is not None else None,
        "appRssPeakBytes": maximum(app_rss),
        "appThreadsPeak": maximum(app_threads),
        "heapPeakBytes": maximum(actuator("jvm.heap.used")),
        "nonHeapPeakBytes": maximum(actuator("jvm.nonheap.used")),
        "directMemoryPeakBytes": maximum(actuator("jvm.direct.used")),
        "jvmThreadsPeak": maximum(actuator("jvm.threads.live")),
        "jvmAllocatedBytes": counter_delta(allocated),
        "jvmPromotedBytes": counter_delta(promoted),
        "gcPauseCount": counter_delta(gc_pause_count),
        "gcPauseSeconds": counter_delta(gc_pause_seconds),
        "tomcatBusyPeak": maximum(actuator("tomcat.busy")),
        "nettyPendingTasksPeak": maximum(
            actuator("reactor.pending_tasks") + actuator("reactor.pending_tasks.value")
        ),
        "databaseConnectionsPeak": maximum(postgres("connections_total")),
        "databaseActivePeak": maximum(postgres("connections_active")),
        "databaseWaitingPeak": maximum(postgres("connections_waiting")),
        "databaseCacheHitPercent": (
            hit_delta / (hit_delta + read_delta) * 100
            if hit_delta + read_delta
            else 100.0
        ),
        "databaseCpuP95": percentile(pg_cpu, 0.95),
        "databaseMemoryPeakBytes": maximum(pg_memory),
        "redisCpuP95": percentile(redis_cpu, 0.95),
        "redisMemoryPeakBytes": maximum(redis_memory),
        "redisConnectionsPeak": maximum(redis("connected_clients")),
    }


def generator_resources(rows):
    host_cpu = nested_values(rows, "hostCpu", "normalizedPercent")
    process_cpu = nested_values(rows, "process", "cpuPercent")
    process_rss = nested_values(rows, "process", "rssBytes")
    total = nested_values(rows, "hostMemory", "totalBytes")
    available = nested_values(rows, "hostMemory", "availableBytes")
    pressure = [
        100 * (1 - free / capacity)
        for free, capacity in zip(available, total)
        if capacity > 0
    ]
    received = nested_values(rows, "network", "receivedBytes")
    sent = nested_values(rows, "network", "sentBytes")
    receive_errors = nested_values(rows, "network", "receiveErrors")
    transmit_errors = nested_values(rows, "network", "transmitErrors")
    receive_drops = nested_values(rows, "network", "receiveDrops")
    transmit_drops = nested_values(rows, "network", "transmitDrops")
    elapsed = elapsed_seconds(rows)
    received_bytes = counter_delta(received) if len(received) > 1 else None
    sent_bytes = counter_delta(sent) if len(sent) > 1 else None
    total_bytes = (
        received_bytes + sent_bytes
        if received_bytes is not None and sent_bytes is not None
        else None
    )
    return {
        "samples": len(rows),
        "hostCpuP95": percentile(host_cpu, 0.95),
        "processCpuP95": percentile(process_cpu, 0.95),
        "processRssPeakBytes": maximum(process_rss),
        "memoryUsedPercentPeak": maximum(pressure),
        "networkReceivedBytes": received_bytes,
        "networkSentBytes": sent_bytes,
        "networkAverageMbps": (
            total_bytes * 8 / elapsed / 1_000_000
            if total_bytes is not None and elapsed
            else None
        ),
        "networkReceiveErrors": counter_delta(receive_errors),
        "networkTransmitErrors": counter_delta(transmit_errors),
        "networkReceiveDrops": counter_delta(receive_drops),
        "networkTransmitDrops": counter_delta(transmit_drops),
    }


def validity(generator, failure_rate, dropped, network_capacity_mbps=None):
    reasons = []
    cpu = generator.get("hostCpuP95")
    memory = generator.get("memoryUsedPercentPeak")
    if cpu is None:
        reasons.append("load generator CPU telemetry is missing")
    elif cpu > 80:
        reasons.append(f"load generator CPU p95 is {cpu:.1f}% (>80%)")
    if memory is not None and memory > 95:
        reasons.append(f"load generator memory use peaked at {memory:.1f}%")
    network_mbps = generator.get("networkAverageMbps")
    if (
        network_capacity_mbps
        and network_mbps is not None
        and network_mbps > network_capacity_mbps * 0.95
    ):
        reasons.append(
            f"load generator network averaged {network_mbps:.1f} Mbps "
            f"(>95% of {network_capacity_mbps:.1f} Mbps)"
        )
    network_faults = sum(
        generator.get(name) or 0
        for name in (
            "networkReceiveErrors",
            "networkTransmitErrors",
            "networkReceiveDrops",
            "networkTransmitDrops",
        )
    )
    if network_faults > 0:
        reasons.append(f"load generator network reported {network_faults:.0f} errors/drops")
    if failure_rate is None:
        reasons.append("failure-rate metric is missing")
    return {"valid": not reasons, "reasons": reasons}


def normalize_k6(path, match, root, network_capacity_mbps=None):
    runtime, round_number, scenario, target = match.groups()
    data = json.loads(path.read_text())
    achieved = metric(data, "http_reqs", "rate") or 0.0
    failure = max(
        metric(data, "http_req_failed", "rate") or 0.0,
        metric(data, "response_validation_failed", "rate") or 0.0,
    )
    dropped = metric(data, "dropped_iterations", "count") or 0.0
    target = int(target)
    base = path.stem
    server_rows = jsonl(
        root / "telemetry" / f"k6-{base}.jsonl"
    )
    generator_rows = jsonl(root / "generator-telemetry" / f"{base}.jsonl")
    resources = server_resources(server_rows)
    generator = generator_resources(generator_rows)
    recovery = recovery_summary(root / "recovery" / f"k6-{base}.json")
    saturated = (
        achieved < target * 0.95
        or failure > 0.01
        or dropped > 0
        or (resources.get("databaseWaitingPeak") or 0) > 0
    )
    return {
        "id": f"k6-{base}",
        "tool": "k6",
        "runtime": runtime,
        "round": int(round_number),
        "scenario": scenario,
        "load": {"type": "rps", "value": target},
        "summary": {
            "targetRps": target,
            "achievedRps": achieved,
            "successRps": achieved * (1 - failure),
            "failureRate": failure,
            "dropped": dropped,
            "p50Ms": metric(data, "http_req_duration", "med"),
            "p90Ms": metric(data, "http_req_duration", "p(90)"),
            "p95Ms": metric(data, "http_req_duration", "p(95)"),
            "p99Ms": metric(data, "http_req_duration", "p(99)"),
        },
        "resources": resources,
        "generator": generator,
        "recovery": recovery,
        "classification": {
            "saturated": saturated,
            "sustainable": not saturated and failure < 0.001 and dropped == 0,
        },
        "validity": validity(generator, failure, dropped, network_capacity_mbps),
        "source": str(path.relative_to(root)),
        "timeseries": f"timeseries/{base}.json",
    }


def normalize_ngrinder(path, match, root, network_capacity_mbps=None):
    runtime, round_number, scenario, vusers = match.groups()
    data = json.loads(path.read_text())
    summary = data.get("summary", {})
    failure = float(summary.get("failureRate") or 0)
    base = path.stem.removeprefix("ngrinder-")
    server_rows = jsonl(
        root / "telemetry" / f"ngrinder-{base}.jsonl"
    )
    generator_rows = jsonl(
        root / "generator-telemetry" / f"ngrinder-{base}.jsonl"
    )
    resources = server_resources(server_rows)
    generator = generator_resources(generator_rows)
    recovery = recovery_summary(root / "recovery" / f"ngrinder-{base}.json")
    saturated = failure > 0.01 or (resources.get("databaseWaitingPeak") or 0) > 0
    return {
        "id": f"ngrinder-{base}",
        "tool": "ngrinder",
        "runtime": runtime,
        "round": int(round_number),
        "scenario": scenario,
        "load": {"type": "vusers", "value": int(vusers)},
        "summary": {
            "targetRps": None,
            "achievedRps": float(summary.get("achievedRps") or 0),
            "successRps": float(summary.get("successRps") or 0),
            "failureRate": failure,
            "dropped": None,
            "meanMs": float(summary.get("meanMs") or 0),
            "p50Ms": None,
            "p90Ms": None,
            "p95Ms": None,
            "p99Ms": None,
        },
        "resources": resources,
        "generator": generator,
        "recovery": recovery,
        "classification": {
            "saturated": saturated,
            "sustainable": not saturated and failure < 0.001,
        },
        "validity": validity(generator, failure, None, network_capacity_mbps),
        "source": str(path.relative_to(root)),
        "timeseries": f"timeseries/ngrinder-{base}.json",
    }


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("results_dir", type=Path)
    args = parser.parse_args()
    root = args.results_dir
    metadata = json.loads((root / "metadata.json").read_text())
    network_capacity_mbps = metadata.get("limits", {}).get(
        "loadGeneratorNetworkCapacityMbps"
    )
    runs = []
    for path in sorted((root / "raw").glob("*.json")):
        if path.name.endswith("-warmup.json"):
            continue
        match = K6_PATTERN.match(path.name)
        if match:
            runs.append(normalize_k6(path, match, root, network_capacity_mbps))
            continue
        match = NGRINDER_PATTERN.match(path.name)
        if match:
            runs.append(
                normalize_ngrinder(path, match, root, network_capacity_mbps)
            )
    output = {
        "schemaVersion": 1,
        "metadata": metadata,
        "runs": runs,
        "notes": [
            "nGrinder 3.5.9-p1 does not expose p90/p95/p99 in its stable REST summary; those values remain null.",
            "Network errors and drops always invalidate a run. Link utilization is also checked when GENERATOR_NETWORK_CAPACITY_MBPS is configured.",
        ],
    }
    (root / "normalized-results.json").write_text(json.dumps(output, indent=2) + "\n")
    with (root / "normalized-results.jsonl").open("w") as handle:
        for run in runs:
            handle.write(json.dumps(run, separators=(",", ":")) + "\n")


if __name__ == "__main__":
    main()
