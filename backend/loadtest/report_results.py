#!/usr/bin/env python3
import argparse
import json
import statistics
from collections import defaultdict
from pathlib import Path


def median(rows, path):
    values = []
    for row in rows:
        value = row
        for key in path:
            value = value.get(key) if isinstance(value, dict) else None
        if isinstance(value, (int, float)):
            values.append(float(value))
    return statistics.median(values) if values else None


def display(value, digits=2, suffix=""):
    return "-" if value is None else f"{value:.{digits}f}{suffix}"


def aggregate(runs):
    groups = defaultdict(list)
    for run in runs:
        groups[
            (
                run["tool"],
                run["runtime"],
                run["scenario"],
                run["load"]["type"],
                run["load"]["value"],
            )
        ].append(run)
    output = []
    for key, rows in sorted(groups.items()):
        tool, runtime, scenario, load_type, load = key
        valid_rows = [row for row in rows if row["validity"]["valid"]]
        output.append(
            {
                "tool": tool,
                "runtime": runtime,
                "scenario": scenario,
                "loadType": load_type,
                "load": load,
                "runs": len(rows),
                "validRuns": len(valid_rows),
                "successRps": median(valid_rows, ("summary", "successRps")),
                "failureRate": median(valid_rows, ("summary", "failureRate")),
                "p95Ms": median(valid_rows, ("summary", "p95Ms")),
                "meanMs": median(valid_rows, ("summary", "meanMs")),
                "appCpuP95": median(valid_rows, ("resources", "appCpuP95")),
                "appRssPeakBytes": median(valid_rows, ("resources", "appRssPeakBytes")),
                "dbCpuP95": median(valid_rows, ("resources", "databaseCpuP95")),
                "dbWaitingPeak": median(valid_rows, ("resources", "databaseWaitingPeak")),
                "generatorCpuP95": median(valid_rows, ("generator", "hostCpuP95")),
                "recoveryFailedSamples": median(
                    valid_rows, ("recovery", "failedSamples")
                ),
                "saturated": (
                    sum(
                        1
                        for row in valid_rows
                        if row["classification"]["saturated"]
                    )
                    > len(valid_rows) / 2
                    if valid_rows
                    else None
                ),
            }
        )
    return output


def paired_deltas(runs, tool, metric_path):
    groups = defaultdict(lambda: defaultdict(dict))
    for run in runs:
        if run["tool"] != tool or not run["validity"]["valid"]:
            continue
        key = (run["scenario"], run["load"]["type"], run["load"]["value"])
        groups[key][run["round"]][run["runtime"]] = run
    output = {}
    for key, rounds in groups.items():
        deltas = []
        for pair in rounds.values():
            if "mvc" not in pair or "webflux" not in pair:
                continue
            baseline = pair["mvc"]
            current = pair["webflux"]
            for path_key in metric_path:
                baseline = baseline.get(path_key) if isinstance(baseline, dict) else None
                current = current.get(path_key) if isinstance(current, dict) else None
            if isinstance(baseline, (int, float)) and baseline != 0 and isinstance(
                current, (int, float)
            ):
                deltas.append((current - baseline) / baseline * 100)
        output[key] = deltas
    return output


def common_direction(runs):
    directions = {}
    for tool in ("k6", "ngrinder"):
        decisive = []
        for deltas in paired_deltas(
            runs, tool, ("summary", "successRps")
        ).values():
            if len(deltas) < 3:
                continue
            if all(delta >= 5 for delta in deltas):
                decisive.append("webflux")
            elif all(delta <= -5 for delta in deltas):
                decisive.append("mvc")
        if decisive and len(set(decisive)) == 1:
            directions[tool] = decisive[0]
        elif decisive:
            directions[tool] = "mixed"
    if len(directions) < 2:
        return "두 도구에서 세 번 반복된 5% 이상 차이가 모두 재현되지 않아 공통 결론을 내리지 않습니다."
    if directions["k6"] == directions["ngrinder"] and directions["k6"] in {
        "webflux",
        "mvc",
    }:
        winner = "WebFlux/R2DBC" if directions["k6"] == "webflux" else "MVC/JDBC"
        return f"두 도구 모두 성공 처리량 기준으로 **{winner}** 우세 방향을 재현했습니다."
    return "k6와 nGrinder의 방향이 일치하지 않아 런타임 우열을 확정하지 않습니다."


def regression_findings(runs):
    findings = []
    for key, deltas in paired_deltas(
        runs, "k6", ("summary", "successRps")
    ).items():
        if len(deltas) >= 3 and all(delta <= -5 for delta in deltas):
            findings.append(
                f"`k6 {key[0]} {key[2]} {key[1]}`: WebFlux 성공 RPS가 "
                f"세 번 모두 5% 이상 하락 ({', '.join(f'{value:.1f}%' for value in deltas)})."
            )
    for key, deltas in paired_deltas(runs, "k6", ("summary", "p95Ms")).items():
        if len(deltas) >= 3 and all(delta >= 10 for delta in deltas):
            findings.append(
                f"`k6 {key[0]} {key[2]} {key[1]}`: WebFlux p95가 "
                f"세 번 모두 10% 이상 상승 ({', '.join(f'{value:.1f}%' for value in deltas)})."
            )
    return findings


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("results_dir", type=Path)
    args = parser.parse_args()
    data = json.loads((args.results_dir / "normalized-results.json").read_text())
    metadata = data["metadata"]
    rows = aggregate(data["runs"])
    invalid = [run for run in data["runs"] if not run["validity"]["valid"]]

    lines = [
        "# BuddyStudy MVC/JDBC vs WebFlux/R2DBC Performance Report",
        "",
        "## Experiment",
        "",
        f"- Profile: `{metadata['profile']}`",
        f"- Tools: `{metadata['tool']}`",
        f"- MVC ref: `{metadata['refs']['mvc']}`",
        f"- WebFlux ref: `{metadata['refs']['webflux']}`",
        f"- Scenarios: `{', '.join(metadata['execution']['scenarios'])}`",
        f"- JVM limit: `{metadata['limits']['visibleCpu']} CPU`, heap `{metadata['limits']['jvmHeap']}`",
        f"- DB pool: `{metadata['limits']['databasePool']}`",
        f"- Load generator: `{metadata['execution']['loadGenerator']}`",
        "",
        "## Result",
        "",
        common_direction(data["runs"]),
        "",
        "| Tool | Scenario | Load | Runtime | Valid | Success RPS | Error % | p95 / mean ms | App CPU p95 | RSS peak MiB | DB CPU p95 | DB waits | Generator CPU p95 | Recovery failures | Saturated |",
        "| --- | --- | ---: | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | --- |",
    ]
    for row in rows:
        latency = row["p95Ms"] if row["tool"] == "k6" else row["meanMs"]
        lines.append(
            "| {tool} | {scenario} | {load} {load_type} | {runtime} | {valid}/{runs} | {rps} | {error} | {latency} | {cpu} | {rss} | {db_cpu} | {db_wait} | {gen_cpu} | {recovery_failures} | {saturated} |".format(
                tool=row["tool"],
                scenario=row["scenario"],
                load=row["load"],
                load_type="RPS" if row["loadType"] == "rps" else "VU",
                runtime=row["runtime"],
                valid=row["validRuns"],
                runs=row["runs"],
                rps=display(row["successRps"]),
                error=display((row["failureRate"] or 0) * 100, 3),
                latency=display(latency),
                cpu=display(row["appCpuP95"], 1, "%"),
                rss=display(
                    row["appRssPeakBytes"] / 1024**2
                    if row["appRssPeakBytes"] is not None
                    else None,
                    1,
                ),
                db_cpu=display(row["dbCpuP95"], 1, "%"),
                db_wait=display(row["dbWaitingPeak"], 0),
                gen_cpu=display(row["generatorCpuP95"], 1, "%"),
                recovery_failures=display(row["recoveryFailedSamples"], 0),
                saturated=(
                    "yes"
                    if row["saturated"] is True
                    else "no" if row["saturated"] is False else "-"
                ),
            )
        )

    regressions = regression_findings(data["runs"])
    lines.extend(["", "## Regressions", ""])
    if regressions:
        lines.extend(f"- {finding}" for finding in regressions)
    else:
        lines.append(
            "- 세 번 모두 재현된 성공 RPS 5% 이상 하락 또는 k6 p95 10% 이상 상승이 없습니다."
        )

    lines.extend(
        [
            "",
            "## Validity",
            "",
            f"- Total runs: {len(data['runs'])}",
            f"- Invalid runs: {len(invalid)}",
            "- A run is invalid when load-generator CPU p95 exceeds 80%, memory use exceeds 95%, NIC errors/drops occur, configured link utilization reaches 95%, or mandatory generator telemetry is missing.",
            "- k6 saturation: achieved throughput below 95% of target, errors above 1%, dropped iterations, or observed MySQL waiting queries.",
            "- Sustainable stage: error rate below 0.1%, no dropped iterations, and no saturation signal.",
        ]
    )
    for run in invalid:
        lines.append(f"- `{run['id']}`: {'; '.join(run['validity']['reasons'])}")
    lines.extend(
        [
            "",
            "## Interpretation Rules",
            "",
            "- Open-loop k6 capacity and closed-loop nGrinder sustained behavior are reported separately.",
            "- A 5% throughput decrease or 10% p95 increase is a regression only when all three standard rounds reproduce the same direction.",
            "- nGrinder's stable REST result does not expose p90/p95/p99; its mean latency is shown and no percentile is fabricated.",
            "- Raw summaries, one-second time series, server telemetry, and load-generator telemetry remain in this result directory.",
        ]
    )
    (args.results_dir / "REPORT.md").write_text("\n".join(lines) + "\n")


if __name__ == "__main__":
    main()
