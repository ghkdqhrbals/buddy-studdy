#!/usr/bin/env python3
import argparse
import base64
import json
import os
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from datetime import datetime, timezone
from pathlib import Path
from urllib.parse import urlsplit

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from validate_scenarios import validate_manifest


FINISHED_STATUSES = {
    "FINISHED",
    "FINISHED_WITH_WARNING",
    "STOP_BY_ERROR",
    "STOP_ON_ERROR",
    "CANCELED",
}


def execution_shape(vusers: int, max_processes: int, max_threads_per_process: int) -> tuple[int, int]:
    if vusers <= 0 or max_processes <= 0 or max_threads_per_process <= 0:
        raise ValueError("nGrinder execution limits must be positive")
    if vusers <= max_threads_per_process:
        return 1, vusers

    minimum_processes = (vusers + max_threads_per_process - 1) // max_threads_per_process
    for processes in range(min(max_processes, vusers), minimum_processes - 1, -1):
        if vusers % processes == 0:
            return processes, vusers // processes
    raise ValueError(
        f"{vusers} VUsers cannot be split exactly across at most {max_processes} processes "
        f"with at most {max_threads_per_process} threads each"
    )


class NGrinderClient:
    def __init__(self, base_url: str, username: str, password: str):
        self.base_url = base_url.rstrip("/")
        token = base64.b64encode(f"{username}:{password}".encode()).decode()
        self.headers = {
            "Authorization": f"Basic {token}",
            "Accept": "application/json",
            "WWW-Authenticate": "Basic",
        }

    def request(self, method: str, path: str, payload=None):
        body = None
        headers = dict(self.headers)
        if payload is not None:
            body = json.dumps(payload, separators=(",", ":")).encode()
            headers["Content-Type"] = "application/json"
        request = urllib.request.Request(f"{self.base_url}{path}", data=body, headers=headers, method=method)
        try:
            with urllib.request.urlopen(request, timeout=30) as response:
                raw = response.read()
                return json.loads(raw) if raw else None
        except urllib.error.HTTPError as error:
            details = error.read().decode(errors="replace")
            raise RuntimeError(f"nGrinder {method} {path} failed with {error.code}: {details}") from error


def wait_for_controller(client: NGrinderClient, timeout_seconds: int) -> None:
    deadline = time.monotonic() + timeout_seconds
    while time.monotonic() < deadline:
        try:
            client.request("GET", "/perftest/api?size=1")
            return
        except (OSError, RuntimeError):
            time.sleep(2)
    raise RuntimeError("nGrinder controller did not become ready")


def wait_for_agent(client: NGrinderClient, timeout_seconds: int) -> None:
    deadline = time.monotonic() + timeout_seconds
    while time.monotonic() < deadline:
        try:
            agents = client.request("GET", "/agent/api")
            if any(
                agent.get("approved")
                and agent.get("state", {}).get("ready")
                for agent in (agents or [])
            ):
                return
        except (OSError, RuntimeError):
            pass
        time.sleep(2)
    raise RuntimeError("nGrinder agent did not become ready")


def create_test(client: NGrinderClient, payload: dict, timeout_seconds: int) -> dict:
    deadline = time.monotonic() + timeout_seconds
    latest_error = None
    while time.monotonic() < deadline:
        try:
            return client.request("POST", "/perftest/api", payload)
        except RuntimeError as error:
            latest_error = error
            message = str(error).lower()
            retryable = any(
                marker in message
                for marker in (
                    "not enough agent",
                    "agent is not ready",
                    "no free agent",
                )
            )
            if not retryable:
                raise
            time.sleep(2)
    raise RuntimeError(f"nGrinder agent did not become ready: {latest_error}")


def save_script(client: NGrinderClient, script_path: Path, target_url: str, encoded_configuration: str) -> str:
    file_name = "BuddyStudyLoadTest.groovy"
    created = client.request(
        "POST",
        "/script/api/new/?type=script",
        {
            "fileName": file_name,
            "testUrl": target_url,
            "options": None,
            "scriptType": "groovy",
            "createLibAndResource": False,
        },
    )
    file_entry = created["file"]

    source = script_path.read_text()
    marker = "__BUDDYSTUDY_CONFIG_BASE64__"
    if marker not in source:
        raise RuntimeError(f"nGrinder script is missing configuration marker: {marker}")
    file_entry["content"] = source.replace(marker, encoded_configuration)
    file_entry["description"] = "BuddyStudy reusable API load test"
    client.request(
        "POST",
        f"/script/api/save/{urllib.parse.quote(file_name)}",
        {
            "fileEntry": file_entry,
            "targetHosts": urlsplit(target_url).hostname or "",
            "validated": "0",
            "createLibAndResource": False,
        },
    )
    return file_entry["path"]


def expanded_requests(manifest: dict, scenario: str, studies_limit: int) -> list[dict]:
    requests = []
    for definition in manifest["scenarios"][scenario]["requests"]:
        row = dict(definition)
        row["path"] = row["path"].replace("${STUDIES_LIMIT}", str(studies_limit))
        row.setdefault("nonEmptyJsonPaths", [])
        requests.append(row)
    return requests


def status_name(value) -> str:
    if isinstance(value, dict):
        return str(value.get("name", "UNKNOWN"))
    return str(value or "UNKNOWN")


def graph_points(graph: dict) -> list[dict]:
    if not isinstance(graph, dict):
        return []
    candidates = {}
    for metric_name, value in graph.items():
        rows = value.get(metric_name) if isinstance(value, dict) else value
        if not isinstance(rows, list):
            continue
        candidates[metric_name] = rows
    if not candidates:
        return []
    length = max(len(rows) for rows in candidates.values())
    output = []
    for index in range(length):
        point = {"elapsedSeconds": index}
        for metric_name, rows in candidates.items():
            if index >= len(rows):
                continue
            row = rows[index]
            if isinstance(row, (int, float)):
                value = float(row)
            elif isinstance(row, list) and row:
                value = float(row[-1])
            elif isinstance(row, dict):
                value = next(
                    (float(item) for item in reversed(list(row.values())) if isinstance(item, (int, float))),
                    0.0,
                )
            else:
                continue
            normalized = {
                "TPS": "rps",
                "Tests": "requests",
                "Errors": "errors",
                "Mean_Test_Time_(ms)": "meanMs",
            }.get(metric_name, metric_name)
            point[normalized] = value
        output.append(point)
    return output


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--controller-url", default="http://127.0.0.1:18081")
    parser.add_argument("--username", default=os.environ.get("NGRINDER_USERNAME", "admin"))
    parser.add_argument("--password", default=os.environ.get("NGRINDER_PASSWORD", "admin"))
    parser.add_argument("--manifest", type=Path, default=Path(__file__).resolve().parents[1] / "scenarios.json")
    parser.add_argument("--script", type=Path, default=Path(__file__).with_name("BuddyStudyLoadTest.groovy"))
    parser.add_argument("--base-url", required=True)
    parser.add_argument("--access-token-file", type=Path)
    parser.add_argument("--scenario", required=True)
    parser.add_argument("--vusers", type=int, required=True)
    parser.add_argument("--max-processes", type=int, default=4)
    parser.add_argument("--max-threads-per-process", type=int, default=250)
    parser.add_argument("--ramp-seconds", type=int, default=30)
    parser.add_argument("--hold-seconds", type=int, default=180)
    parser.add_argument("--timeout-ms", type=int, default=5000)
    parser.add_argument("--studies-limit", type=int, default=100)
    parser.add_argument("--validate-body", action="store_true")
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--timeseries-output", type=Path, required=True)
    parser.add_argument("--controller-timeout", type=int, default=180)
    args = parser.parse_args()

    manifest = validate_manifest(args.manifest)
    if args.scenario not in manifest["scenarios"]:
        raise SystemExit(f"unknown scenario: {args.scenario}")
    if args.vusers <= 0:
        raise SystemExit("vusers must be positive")
    try:
        process_count, threads_per_process = execution_shape(
            args.vusers,
            args.max_processes,
            args.max_threads_per_process,
        )
    except ValueError as error:
        raise SystemExit(str(error)) from error
    requires_auth = any(
        request["authenticated"] for request in manifest["scenarios"][args.scenario]["requests"]
    )
    access_token = args.access_token_file.read_text().strip() if args.access_token_file else ""
    if requires_auth and not access_token:
        raise SystemExit(f"{args.scenario} requires --access-token-file")

    client = NGrinderClient(args.controller_url, args.username, args.password)
    wait_for_controller(client, args.controller_timeout)
    wait_for_agent(client, args.controller_timeout)
    configuration = {
        "baseUrl": args.base_url.rstrip("/"),
        "accessToken": access_token,
        "scenario": args.scenario,
        "timeoutMs": args.timeout_ms,
        "validateBody": args.validate_body,
        "requests": expanded_requests(manifest, args.scenario, args.studies_limit),
    }
    encoded_configuration = base64.b64encode(
        json.dumps(configuration, separators=(",", ":")).encode()
    ).decode()
    script_name = save_script(client, args.script, args.base_url, encoded_configuration)
    ramp_step = max(1, args.vusers // max(1, args.ramp_seconds))
    payload = {
        "testName": f"BuddyStudy {args.scenario} {args.vusers} VU",
        "description": "Automated BuddyStudy MVC/WebFlux comparison",
        "status": "READY",
        "threshold": "D",
        "duration": (args.ramp_seconds + args.hold_seconds) * 1000,
        "agentCount": 1,
        "vuserPerAgent": args.vusers,
        "processes": process_count,
        "threads": threads_per_process,
        "useRampUp": args.ramp_seconds > 0,
        "rampUpType": "THREAD",
        "rampUpInitCount": 1,
        "rampUpInitSleepTime": 0,
        "rampUpStep": ramp_step,
        "rampUpIncrementInterval": 1000,
        "samplingInterval": 1,
        "ignoreSampleCount": 0,
        "scriptName": script_name,
        "scriptRevision": "-1",
        "targetHosts": urlsplit(args.base_url).hostname or "",
        "region": "NONE",
        "connectionReset": False,
        "ignoreTooManyError": False,
        "param": "",
    }
    started_at = datetime.now(timezone.utc)
    test = create_test(client, payload, args.controller_timeout)
    test_id = int(test["id"])
    deadline = time.monotonic() + args.ramp_seconds + args.hold_seconds + 300
    samples = []
    final_status = "UNKNOWN"
    while time.monotonic() < deadline:
        sample = client.request("GET", f"/perftest/api/{test_id}/sample")
        final_status = status_name(sample.get("status"))
        samples.append(
            {
                "timestamp": datetime.now(timezone.utc).isoformat(),
                "status": final_status,
                "sample": sample.get("perf", {}),
            }
        )
        if final_status in FINISHED_STATUSES:
            break
        time.sleep(1)
    if final_status not in FINISHED_STATUSES:
        try:
            client.request("PUT", f"/perftest/api/{test_id}?action=stop")
        finally:
            raise RuntimeError(f"nGrinder test {test_id} timed out in status {final_status}")

    final = client.request("GET", f"/perftest/api/{test_id}")
    graph = client.request(
        "GET",
        f"/perftest/api/{test_id}/graph?"
        + urllib.parse.urlencode(
            {
                "dataType": "TPS,Tests,Errors,Mean_Test_Time_(ms)",
                "onlyTotal": "true",
                "imgWidth": "1800",
            }
        ),
    )
    errors = int(final.get("errors") or 0)
    tests = int(final.get("tests") or 0)
    tps = float(final.get("tps") or 0.0)
    attempts = tests + errors
    failure_rate = errors / attempts if attempts else 0.0
    output = {
        "schemaVersion": 1,
        "tool": "ngrinder",
        "testId": test_id,
        "scenario": args.scenario,
        "vusers": args.vusers,
        "executionShape": {
            "agentCount": 1,
            "processes": process_count,
            "threadsPerProcess": threads_per_process,
        },
        "rampSeconds": args.ramp_seconds,
        "holdSeconds": args.hold_seconds,
        "startedAt": started_at.isoformat(),
        "finishedAt": datetime.now(timezone.utc).isoformat(),
        "status": final_status,
        "summary": {
            "achievedRps": tps,
            "successRps": tps,
            "meanMs": float(final.get("meanTestTime") or 0.0),
            "peakRps": float(final.get("peakTps") or 0.0),
            "failureRate": failure_rate,
            "errors": errors,
            "requests": attempts,
            "successfulRequests": tests,
        },
        "raw": {
            "test": final,
            "graph": graph,
            "samples": samples,
        },
    }
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.timeseries_output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(output, separators=(",", ":")) + "\n")
    args.timeseries_output.write_text(json.dumps(graph_points(graph), separators=(",", ":")) + "\n")
    if final_status not in {"FINISHED", "FINISHED_WITH_WARNING"}:
        raise SystemExit(f"nGrinder test finished with {final_status}")


if __name__ == "__main__":
    main()
