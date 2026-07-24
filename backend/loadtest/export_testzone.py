#!/usr/bin/env python3
import argparse
import json
import re
import statistics
from datetime import datetime, timezone
from pathlib import Path


TIMESTAMP_PATTERN = re.compile(r"^(\d{8})T(\d{6})Z")


def percentile(values, quantile):
    values = sorted(float(value) for value in values if isinstance(value, (int, float)))
    if not values:
        return None
    index = (len(values) - 1) * quantile
    lower = int(index)
    upper = min(lower + 1, len(values) - 1)
    fraction = index - lower
    return values[lower] + (values[upper] - values[lower]) * fraction


def run_timestamp(name, path):
    match = TIMESTAMP_PATTERN.match(name)
    if match:
        return datetime.strptime("".join(match.groups()), "%Y%m%d%H%M%S").replace(
            tzinfo=timezone.utc
        ).isoformat()
    return datetime.fromtimestamp(path.stat().st_mtime, timezone.utc).isoformat()


def profile_for(name):
    lowered = name.lower()
    if "smoke" in lowered:
        return "smoke"
    if "bottleneck" in lowered or "diagnostic" in lowered:
        return "diagnostic"
    if "soak" in lowered:
        return "soak"
    return "standard"


def refs_from_report(path):
    if not path.exists():
        return {}
    text = path.read_text()
    refs = {}
    for key, label in (("mvc", "MVC ref"), ("webflux", "WebFlux ref")):
        match = re.search(rf"{label}:\s*`([^`]+)`", text)
        if match:
            refs[key] = match.group(1)
    return refs


def legacy_resources(runtime):
    telemetry = runtime.get("telemetry") or []
    cpu = [row.get("cpuPercent") for row in telemetry]
    rss = [row.get("rssMiB") for row in telemetry]
    threads = [row.get("osThreads") for row in telemetry]
    jvm_threads = [row.get("jvmThreads") for row in telemetry]
    heap = [row.get("heapMiB") for row in telemetry]
    return {
        "appCpuP95": percentile(cpu, 0.95),
        "appRssPeakBytes": max((value for value in rss if value is not None), default=None)
        * 1048576
        if any(value is not None for value in rss)
        else None,
        "appThreadsPeak": max(
            (value for value in threads if value is not None), default=None
        ),
        "jvmThreadsPeak": max(
            (value for value in jvm_threads if value is not None), default=None
        ),
        "heapPeakBytes": max(
            (value for value in heap if value is not None), default=None
        )
        * 1048576
        if any(value is not None for value in heap)
        else None,
    }


def legacy_results(path):
    data = json.loads(path.read_text())
    results = []
    for scenario, loads in data.items():
        if scenario == "health":
            continue
        for target, runtimes in loads.items():
            for runtime_name, runtime in runtimes.items():
                summary = runtime.get("summary") or {}
                p95 = summary.get("p95Ms")
                failure = float(summary.get("failureRate") or 0)
                dropped = float(summary.get("dropped") or 0)
                success = float(summary.get("successRps") or 0)
                target_value = int(target)
                results.append(
                    {
                        "id": f"k6-{runtime_name}-{scenario}-rps{target}",
                        "tool": "k6",
                        "runtime": runtime_name,
                        "scenario": scenario,
                        "load": {"type": "rps", "value": target_value},
                        "summary": {
                            **summary,
                            "allRequestP95Ms": p95,
                            "successfulRequestP95Ms": None,
                            "timeoutRate": None,
                            "timeoutCount": None,
                        },
                        "resources": legacy_resources(runtime),
                        "classification": {
                            "saturated": (
                                success < target_value * 0.95
                                or failure > 0.01
                                or dropped > 0
                            ),
                            "timeoutBoundaryReached": (
                                isinstance(p95, (int, float)) and p95 >= 4990
                            ),
                        },
                        "validity": {"valid": True, "reasons": []},
                    }
                )
    return results


def normalized_results(path):
    payload = json.loads(path.read_text())
    results = []
    for run in payload.get("runs", []):
        if run.get("scenario") == "health":
            continue
        summary = dict(run.get("summary") or {})
        summary.setdefault("allRequestP95Ms", summary.get("p95Ms"))
        summary.setdefault("successfulRequestP95Ms", None)
        summary.setdefault("timeoutRate", None)
        summary.setdefault("timeoutCount", None)
        result = dict(run)
        result["summary"] = summary
        result["classification"] = {
            **(run.get("classification") or {}),
            "timeoutBoundaryReached": (
                isinstance(summary.get("allRequestP95Ms"), (int, float))
                and summary["allRequestP95Ms"] >= 4990
            ),
        }
        results.append(result)
    return results


def verdict(results):
    valid = [result for result in results if result.get("validity", {}).get("valid", True)]
    if not valid:
        return "invalid"
    if any(result.get("classification", {}).get("saturated") for result in valid):
        return "failed"
    return "passed"


def findings(results):
    output = []
    timeout_results = [
        result
        for result in results
        if result.get("classification", {}).get("timeoutBoundaryReached")
    ]
    if timeout_results:
        scenarios = sorted({result["scenario"] for result in timeout_results})
        runtimes = sorted({result["runtime"] for result in timeout_results})
        output.append(
            {
                "severity": "critical",
                "title": "Client timeout boundary reached",
                "detail": (
                    f"All-request p95 reached the 5-second timeout in "
                    f"{', '.join(scenarios)} for {', '.join(runtimes)}."
                ),
            }
        )
    saturated = [
        result
        for result in results
        if result.get("classification", {}).get("saturated")
    ]
    if saturated:
        output.append(
            {
                "severity": "warning",
                "title": "Capacity target missed",
                "detail": (
                    f"{len(saturated)} measured runtime/load combinations were saturated."
                ),
            }
        )
    invalid = [
        result
        for result in results
        if not result.get("validity", {}).get("valid", True)
    ]
    if invalid:
        output.append(
            {
                "severity": "warning",
                "title": "Generator validity failed",
                "detail": f"{len(invalid)} runs must be excluded from conclusions.",
            }
        )
    if not output:
        output.append(
            {
                "severity": "good",
                "title": "No saturation detected",
                "detail": "All valid measured combinations remained within the configured limits.",
            }
        )
    return output


def load_execution(root, name):
    directory = root / name
    normalized = directory / "normalized-results.json"
    legacy = directory / "DASHBOARD_DATA.json"
    if normalized.exists():
        results = normalized_results(normalized)
        metadata = json.loads(normalized.read_text()).get("metadata") or {}
        refs = metadata.get("refs") or refs_from_report(directory / "REPORT.md")
    elif legacy.exists():
        results = legacy_results(legacy)
        metadata = {}
        refs = refs_from_report(directory / "REPORT.md")
    else:
        return None
    if not results:
        return None
    return {
        "id": name,
        "startedAt": run_timestamp(name, directory),
        "profile": profile_for(name),
        "status": verdict(results),
        "refs": refs,
        "metadata": metadata,
        "results": results,
        "findings": findings(results),
    }


def summarize_project(executions):
    results = [result for execution in executions for result in execution["results"]]
    valid = [result for result in results if result.get("validity", {}).get("valid", True)]
    successful_rps = [
        result.get("summary", {}).get("successRps")
        for result in valid
        if isinstance(result.get("summary", {}).get("successRps"), (int, float))
    ]
    p95 = [
        result.get("summary", {}).get("allRequestP95Ms")
        for result in valid
        if isinstance(result.get("summary", {}).get("allRequestP95Ms"), (int, float))
    ]
    cpu = [
        result.get("resources", {}).get("appCpuP95")
        for result in valid
        if isinstance(result.get("resources", {}).get("appCpuP95"), (int, float))
    ]
    return {
        "executionCount": len(executions),
        "measurementCount": len(results),
        "validMeasurementCount": len(valid),
        "latestStatus": executions[0]["status"] if executions else "unknown",
        "maxSuccessfulRps": max(successful_rps, default=None),
        "maxAllRequestP95Ms": max(p95, default=None),
        "maxAppCpuP95": max(cpu, default=None),
    }


def export(config_path, results_root, output_path):
    config = json.loads(config_path.read_text())
    projects = []
    executions = []
    for project in config.get("projects", []):
        project_executions = []
        for directory in project.get("resultDirectories", []):
            execution = load_execution(results_root, directory)
            if execution is None:
                continue
            execution["projectId"] = project["id"]
            project_executions.append(execution)
        project_executions.sort(key=lambda item: item["startedAt"], reverse=True)
        executions.extend(project_executions)
        projects.append(
            {
                **{key: value for key, value in project.items() if key != "resultDirectories"},
                "summary": summarize_project(project_executions),
                "latestExecutionId": (
                    project_executions[0]["id"] if project_executions else None
                ),
            }
        )
    payload = {
        "schemaVersion": 1,
        "generatedAt": datetime.now(timezone.utc).isoformat(),
        "projects": projects,
        "executions": executions,
    }
    output_path.parent.mkdir(parents=True, exist_ok=True)
    output_path.write_text(json.dumps(payload, indent=2) + "\n")
    return payload


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--config",
        type=Path,
        default=Path(__file__).with_name("testzone-projects.json"),
    )
    parser.add_argument(
        "--results-root",
        type=Path,
        default=Path(__file__).with_name("results"),
    )
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    export(args.config, args.results_root, args.output)


if __name__ == "__main__":
    main()
