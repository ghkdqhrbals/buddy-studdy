#!/usr/bin/env python3
import argparse
import json
import statistics
from datetime import datetime
from pathlib import Path


def metric(data, name, key):
    return float(data["metrics"][name]["values"].get(key, 0.0))


def load_results(directory, runtime, rounds, scenario):
    rows = []
    for round_number in range(1, rounds + 1):
        path = directory / "raw" / f"{runtime}-round{round_number}-{scenario}.json"
        with path.open() as handle:
            data = json.load(handle)
        rows.append(
            {
                "rps": metric(data, "http_reqs", "rate"),
                "p50": metric(data, "http_req_duration", "med"),
                "p95": metric(data, "http_req_duration", "p(95)"),
                "p99": metric(data, "http_req_duration", "p(99)"),
                "failure_rate": metric(data, "http_req_failed", "rate"),
            }
        )
    return {key: statistics.median(row[key] for row in rows) for key in rows[0]}


def delta(current, baseline, lower_is_better=False):
    if baseline == 0:
        return 0.0
    raw = ((current - baseline) / baseline) * 100
    return -raw if lower_is_better else raw


def load_rss(directory, runtime, rounds):
    samples = []
    for round_number in range(1, rounds + 1):
        path = directory / "raw" / f"{runtime}-round{round_number}-rss-kb.txt"
        samples.append(float(path.read_text().strip()))
    return statistics.median(samples) / 1024


def percentile(values, percentile_value):
    if not values:
        return 0.0
    ordered = sorted(values)
    index = (len(ordered) - 1) * percentile_value
    lower = int(index)
    upper = min(lower + 1, len(ordered) - 1)
    fraction = index - lower
    return ordered[lower] + (ordered[upper] - ordered[lower]) * fraction


def sample_values(samples, section, key):
    values = []
    for sample in samples:
        value = sample.get(section, {}).get(key)
        if isinstance(value, (int, float)):
            values.append(float(value))
    return values


def container_values(samples, container, key):
    values = []
    for sample in samples:
        value = sample.get("containers", {}).get(container, {}).get(key)
        if isinstance(value, (int, float)):
            values.append(float(value))
    return values


def median_value(values):
    return statistics.median(values) if values else 0.0


def max_value(values):
    return max(values) if values else 0.0


def counter_delta(values):
    return max(0.0, values[-1] - values[0]) if len(values) >= 2 else 0.0


def load_telemetry_run(directory, runtime, round_number, scenario):
    path = directory / "telemetry" / f"{runtime}-round{round_number}-{scenario}.jsonl"
    samples = []
    if path.exists():
        for line in path.read_text().splitlines():
            try:
                samples.append(json.loads(line))
            except ValueError:
                continue
    if not samples:
        return {}

    timestamps = []
    for sample in samples:
        try:
            timestamps.append(datetime.fromisoformat(sample["timestamp"]))
        except (KeyError, ValueError):
            continue
    measured_seconds = max(1.0, (timestamps[-1] - timestamps[0]).total_seconds()) if len(timestamps) >= 2 else 1.0

    process_cpu = sample_values(samples, "process", "cpu_percent")
    process_rss = sample_values(samples, "process", "rss_bytes")
    process_threads = sample_values(samples, "process", "os_threads")
    load_cpu = sample_values(samples, "load_generator", "cpu_percent")
    load_rss = sample_values(samples, "load_generator", "rss_bytes")
    heap = sample_values(samples, "actuator", "jvm.heap.used")
    nonheap = sample_values(samples, "actuator", "jvm.nonheap.used")
    direct = sample_values(samples, "actuator", "jvm.direct.used")
    jvm_threads = sample_values(samples, "actuator", "jvm.threads.live")
    normalized_cpu = sample_values(samples, "actuator", "process.cpu")
    system_cpu = sample_values(samples, "actuator", "system.cpu")
    gc_count = sample_values(samples, "actuator", "jvm.gc.pause.count")
    gc_time = sample_values(samples, "actuator", "jvm.gc.pause.total_time")
    gc_max = sample_values(samples, "actuator", "jvm.gc.pause.max")
    allocated = sample_values(samples, "actuator", "jvm.gc.allocated.count")
    pg_blks_read = sample_values(samples, "postgres", "blks_read")
    pg_blks_hit = sample_values(samples, "postgres", "blks_hit")
    block_reads = counter_delta(pg_blks_read)
    block_hits = counter_delta(pg_blks_hit)

    return {
        "samples": len(samples),
        "process_cpu_median": median_value(process_cpu),
        "process_cpu_p95": percentile(process_cpu, 0.95),
        "process_cpu_normalized_median": median_value(normalized_cpu) * 100,
        "system_cpu_median": median_value(system_cpu) * 100,
        "load_cpu_median": median_value(load_cpu),
        "load_rss_peak_mib": max_value(load_rss) / 1024**2,
        "rss_median_mib": median_value(process_rss) / 1024**2,
        "rss_peak_mib": max_value(process_rss) / 1024**2,
        "os_threads_peak": max_value(process_threads),
        "jvm_threads_peak": max_value(jvm_threads),
        "heap_peak_mib": max_value(heap) / 1024**2,
        "nonheap_peak_mib": max_value(nonheap) / 1024**2,
        "direct_peak_mib": max_value(direct) / 1024**2,
        "gc_count": counter_delta(gc_count),
        "gc_time_ms": counter_delta(gc_time) * 1000,
        "gc_max_ms": max_value(gc_max) * 1000,
        "allocation_mib_per_second": (counter_delta(allocated) / 1024**2) / measured_seconds,
        "hikari_active_max": max_value(sample_values(samples, "actuator", "hikari.active")),
        "hikari_pending_max": max_value(sample_values(samples, "actuator", "hikari.pending")),
        "tomcat_busy_max": max_value(sample_values(samples, "actuator", "tomcat.busy")),
        "webflux_active_max": max_value(sample_values(samples, "actuator", "webflux.executor.active")),
        "webflux_queued_max": max_value(sample_values(samples, "actuator", "webflux.executor.queued")),
        "pg_connections_max": max_value(sample_values(samples, "postgres", "connections_total")),
        "pg_active_max": max_value(sample_values(samples, "postgres", "connections_active")),
        "pg_waiting_max": max_value(sample_values(samples, "postgres", "connections_waiting")),
        "pg_cache_hit_percent": (block_hits / (block_hits + block_reads) * 100) if block_hits + block_reads else 100.0,
        "postgres_cpu_median": median_value(container_values(samples, "buddystudy-loadtest-postgres", "cpu_percent")),
        "postgres_memory_peak_mib": max_value(container_values(samples, "buddystudy-loadtest-postgres", "memory_bytes")) / 1024**2,
        "redis_cpu_median": median_value(container_values(samples, "buddystudy-loadtest-redis", "cpu_percent")),
        "redis_memory_peak_mib": max_value(container_values(samples, "buddystudy-loadtest-redis", "memory_bytes")) / 1024**2,
        "redis_ops_max": max_value(sample_values(samples, "redis", "instantaneous_ops_per_sec")),
    }


def load_telemetry(directory, runtime, rounds, scenario):
    rows = [
        load_telemetry_run(directory, runtime, round_number, scenario)
        for round_number in range(1, rounds + 1)
    ]
    rows = [row for row in rows if row]
    if not rows:
        return {}
    return {key: statistics.median(row[key] for row in rows) for key in rows[0]}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("results_dir", type=Path)
    parser.add_argument("--mvc-ref", required=True)
    parser.add_argument("--webflux-ref", required=True)
    parser.add_argument("--rounds", type=int, required=True)
    parser.add_argument("--vus", required=True)
    parser.add_argument("--duration", required=True)
    parser.add_argument("--heap", required=True)
    parser.add_argument("--cpu-count", required=True)
    parser.add_argument("--db-pool", required=True)
    parser.add_argument("--blocking-concurrency", required=True)
    parser.add_argument("--logging", required=True)
    parser.add_argument("--telemetry-interval", required=True)
    parser.add_argument("--jfr", required=True)
    parser.add_argument("--nmt", required=True)
    args = parser.parse_args()

    lines = [
        "# MVC vs WebFlux Load-Test Result",
        "",
        f"- MVC ref: `{args.mvc_ref}`",
        f"- WebFlux ref: `{args.webflux_ref}`",
        f"- Rounds: {args.rounds} (median reported, execution order alternated)",
        f"- Load: {args.vus} constant VUs for {args.duration} per endpoint",
        f"- JVM: `-Xms{args.heap} -Xmx{args.heap} -XX:ActiveProcessorCount={args.cpu_count}`",
        f"- Hikari maximum pool: {args.db_pool}",
        f"- Blocking request concurrency: {args.blocking_concurrency} for both MVC/Tomcat and WebFlux",
        f"- API exchange logging: {args.logging}",
        f"- Telemetry sampling interval: {args.telemetry_interval}s",
        f"- JFR profile recording: {args.jfr}; Native Memory Tracking: {args.nmt}",
        "- Fixture: 1 user, 100 studies, 500 graded public questions",
        "",
        "| Endpoint | Runtime | RPS | p50 ms | p95 ms | p99 ms | Failed |",
        "| --- | --- | ---: | ---: | ---: | ---: | ---: |",
    ]

    comparisons = []
    for scenario in ("health", "public-questions", "studies"):
        mvc = load_results(args.results_dir, "mvc", args.rounds, scenario)
        webflux = load_results(args.results_dir, "webflux", args.rounds, scenario)
        for runtime, values in (("MVC", mvc), ("WebFlux", webflux)):
            lines.append(
                f"| {scenario} | {runtime} | {values['rps']:.1f} | {values['p50']:.2f} | "
                f"{values['p95']:.2f} | {values['p99']:.2f} | {values['failure_rate'] * 100:.3f}% |"
            )
        comparisons.append(
            f"- `{scenario}`: throughput {delta(webflux['rps'], mvc['rps']):+.1f}%, "
            f"p95 latency {delta(webflux['p95'], mvc['p95']):+.1f}%, "
            f"p99 latency {delta(webflux['p99'], mvc['p99']):+.1f}%"
        )

    mvc_rss = load_rss(args.results_dir, "mvc", args.rounds)
    webflux_rss = load_rss(args.results_dir, "webflux", args.rounds)

    lines.extend(
        [
            "",
            "## Relative WebFlux Change",
            "",
            *comparisons,
            f"- Process RSS median: MVC {mvc_rss:.1f} MiB, WebFlux {webflux_rss:.1f} MiB "
            f"({delta(webflux_rss, mvc_rss):+.1f}%)",
            "",
            "Positive latency change means WebFlux is slower. Treat differences below 5% as noise until confirmed on the deployment host with more rounds.",
            "",
            "## Interpretation Rules",
            "",
            "- `health` isolates HTTP runtime and serialization; it does not predict DB-backed API capacity.",
            "- `public-questions` measures JPA reads and a response containing 20 records.",
            "- `studies` measures JWT verification, session/device DB lookup, JPA pagination, and a 100-row response.",
            "- This project still uses blocking JPA. WebFlux should be judged primarily on event-loop safety, tail latency under slow clients, and overload behavior, not only peak RPS.",
            "- Run on an idle machine and compare at 25, 50, 100, and 200 VUs before changing production pool sizes.",
            "- The load generator and backend share one host in this harness. Use a separate load-generator host for production capacity decisions.",
        ]
    )

    telemetry = {
        (runtime, scenario): load_telemetry(args.results_dir, runtime, args.rounds, scenario)
        for runtime in ("mvc", "webflux")
        for scenario in ("health", "public-questions", "studies")
    }
    if any(telemetry.values()):
        lines.extend(
            [
                "",
                "## Runtime Resource Telemetry",
                "",
                "Process CPU uses macOS `ps` semantics and may exceed 100% when multiple cores are busy. RSS includes heap and JVM native memory.",
                "",
                "| Endpoint | Runtime | JVM CPU median / p95 | k6 / host CPU | RSS median / peak MiB | Heap / nonheap / direct peak MiB | OS / JVM threads peak | GC count / total / max ms | Allocation MiB/s |",
                "| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |",
            ]
        )
        for scenario in ("health", "public-questions", "studies"):
            for runtime, label in (("mvc", "MVC"), ("webflux", "WebFlux")):
                row = telemetry[(runtime, scenario)]
                if not row:
                    continue
                lines.append(
                    f"| {scenario} | {label} | {row['process_cpu_median']:.1f}% / {row['process_cpu_p95']:.1f}% | "
                    f"{row['load_cpu_median']:.1f}% / {row['system_cpu_median']:.1f}% | "
                    f"{row['rss_median_mib']:.1f} / {row['rss_peak_mib']:.1f} | "
                    f"{row['heap_peak_mib']:.1f} / {row['nonheap_peak_mib']:.1f} / {row['direct_peak_mib']:.1f} | "
                    f"{row['os_threads_peak']:.0f} / {row['jvm_threads_peak']:.0f} | "
                    f"{row['gc_count']:.0f} / {row['gc_time_ms']:.1f} / {row['gc_max_ms']:.1f} | "
                    f"{row['allocation_mib_per_second']:.1f} |"
                )

        lines.extend(
            [
                "",
                "## Pools and Dependencies",
                "",
                "| Endpoint | Runtime | Hikari active / pending max | Runtime workers active / queued max | PostgreSQL total / active / waiting max | PG cache hit | PG CPU / memory MiB | Redis CPU / memory MiB / ops max |",
                "| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |",
            ]
        )
        for scenario in ("health", "public-questions", "studies"):
            for runtime, label in (("mvc", "MVC"), ("webflux", "WebFlux")):
                row = telemetry[(runtime, scenario)]
                if not row:
                    continue
                worker_active = row["tomcat_busy_max"] if runtime == "mvc" else row["webflux_active_max"]
                worker_queued = 0.0 if runtime == "mvc" else row["webflux_queued_max"]
                lines.append(
                    f"| {scenario} | {label} | {row['hikari_active_max']:.0f} / {row['hikari_pending_max']:.0f} | "
                    f"{worker_active:.0f} / {worker_queued:.0f} | {row['pg_connections_max']:.0f} / "
                    f"{row['pg_active_max']:.0f} / {row['pg_waiting_max']:.0f} | {row['pg_cache_hit_percent']:.2f}% | "
                    f"{row['postgres_cpu_median']:.1f}% / {row['postgres_memory_peak_mib']:.1f} | "
                    f"{row['redis_cpu_median']:.1f}% / {row['redis_memory_peak_mib']:.1f} / {row['redis_ops_max']:.0f} |"
                )

        lines.extend(
            [
                "",
                "JFR recordings and per-scenario thread dumps, heap summaries, and NMT diffs are stored in the sibling `jfr/` and `diagnostics/` directories.",
            ]
        )
    (args.results_dir / "REPORT.md").write_text("\n".join(lines) + "\n")


if __name__ == "__main__":
    main()
