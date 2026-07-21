#!/usr/bin/env python3
import argparse
import json
import statistics
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
    (args.results_dir / "REPORT.md").write_text("\n".join(lines) + "\n")


if __name__ == "__main__":
    main()
