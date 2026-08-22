#!/usr/bin/env python3
"""Collect a bounded, read-only Docker Desktop/Kubernetes RCA snapshot.

Only fixed commands and fixed output formats are used. Raw logs, process IDs,
container IDs, paths, environment values, mounts, device data, and arbitrary
labels are never written to the report or workflow summary.
"""

from __future__ import annotations

import argparse
import hashlib
import heapq
import json
import os
import platform
import re
import selectors
import signal
import stat
import subprocess
import sys
import tempfile
import time
from collections import Counter
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Callable, Mapping, Sequence


EXPECTED_RUNNER_NAME = "macbook-air-buddystudy"
DOCKER_CLI = "/Applications/Docker.app/Contents/Resources/bin/docker"
KUBECTL = "/Applications/Docker.app/Contents/Resources/bin/kubectl"
MAX_CAPTURE_BYTES = 4 * 1024 * 1024
DEFAULT_TIMEOUT_SECONDS = 15
LOG_TIMEOUT_SECONDS = 15
UNIFIED_LOG_TIMEOUT_SECONDS = 20
MAX_RECORDS = 256
TOP_RECORDS = 16
LOG_CAPTURE_BYTES = 16 * 1024 * 1024
UNIFIED_LOG_CAPTURE_BYTES = 32 * 1024 * 1024
DIAGNOSTIC_CHILD_CAPTURE_BYTES = 1024 * 1024
DIAGNOSTIC_FILE_BYTES = 16 * 1024 * 1024
DIAGNOSTIC_TOTAL_BYTES = 64 * 1024 * 1024
DIAGNOSTIC_FILE_LIMIT = 32
DIAGNOSTIC_CANDIDATE_POOL_LIMIT = 128
DIAGNOSTIC_ENTRY_LIMIT_PER_ROOT = 4096
LOOKBACK_HOURS = 6
DIAGNOSTIC_LOOKBACK_SECONDS = LOOKBACK_HOURS * 60 * 60

STATS_FORMAT = (
    '{"name":{{json .Name}},"cpu":{{json .CPUPerc}},'
    '"memory":{{json .MemUsage}},"memoryPercent":{{json .MemPerc}},'
    '"pids":{{json .PIDs}}}'
)
PS_FORMAT = (
    '{"name":{{json .Names}},"state":{{json .State}},'
    '"project":{{json (.Label "com.docker.compose.project")}},'
    '"service":{{json (.Label "com.docker.compose.service")}},'
    '"k8sContainer":{{json (.Label "io.kubernetes.container.name")}},'
    '"k8sPod":{{json (.Label "io.kubernetes.pod.name")}},'
    '"k8sNamespace":{{json (.Label "io.kubernetes.pod.namespace")}}}'
)
POD_JSONPATH = (
    r'{range .items[*]}{.metadata.namespace}{"\t"}{.metadata.name}{"\t"}'
    r'{.status.phase}{"\t"}{range .status.containerStatuses[*]}{.name}{","}'
    r'{.restartCount}{","}{.state.waiting.reason}{","}'
    r'{.lastState.terminated.reason}{","}{.lastState.terminated.finishedAt}'
    r'{";"}{end}{"\n"}{end}'
)
EVENT_COLUMNS = (
    "custom-columns=NAMESPACE:.metadata.namespace,KIND:.involvedObject.kind,"
    "NAME:.involvedObject.name,REASON:.reason,COUNT:.count,LAST:.lastTimestamp"
)
UNIFIED_LOGINWINDOW_PREDICATE = (
    'process == "loginwindow" AND ('
    'eventMessage CONTAINS[c] "Sampling App" OR '
    'eventMessage CONTAINS[c] "setting rSize" OR '
    'eventMessage CONTAINS[c] "app size string" OR '
    'eventMessage CONTAINS[c] "application memory")'
)
UNIFIED_SYSTEM_MEMORY_PREDICATE = (
    'process IN {"kernel", "memorystatusd", "runningboardd", "watchdogd"} AND '
    '(eventMessage CONTAINS[c] "memory pressure" OR '
    'eventMessage CONTAINS[c] "application memory" OR '
    'eventMessage CONTAINS[c] "low memory" OR '
    'eventMessage CONTAINS[c] "out of memory" OR '
    'eventMessage CONTAINS[c] "oom" OR '
    'eventMessage CONTAINS[c] "jetsam" OR '
    'eventMessage CONTAINS[c] "memorystatus" OR '
    'eventMessage CONTAINS[c] "highwater" OR '
    'eventMessage CONTAINS[c] "watchdog" OR '
    'eventMessage CONTAINS[c] "crash" OR '
    'eventMessage CONTAINS[c] "exit" OR '
    'eventMessage CONTAINS[c] "killed")'
)

SAFE_NAME = re.compile(r"[^A-Za-z0-9._+-]+")
LONG_IDENTIFIER = re.compile(r"(?i)(?<![a-z0-9])[a-f0-9]{12,}(?![a-z0-9])")
VERSION_PATTERN = re.compile(r"^[0-9]+(?:\.[0-9]+){1,3}(?:[-+][A-Za-z0-9.-]+)?$")
OS_BUILD_PATTERN = re.compile(r"^[0-9]{2,3}[A-Z][A-Za-z0-9]{1,12}$")
PERCENT_PATTERN = re.compile(r"^([0-9]+(?:\.[0-9]+)?)%$")
SIZE_PATTERN = re.compile(r"([0-9]+(?:\.[0-9]+)?)\s*(B|kB|MB|GB|TB|KiB|MiB|GiB|TiB)\b", re.I)
TIMESTAMP_PATTERN = re.compile(
    r"(20[0-9]{2}-[01][0-9]-[0-3][0-9][T ][0-2][0-9]:[0-5][0-9]:[0-5][0-9](?:\.[0-9]+)?(?:Z|[+-][0-2][0-9]:?[0-5][0-9])?)"
)
DOCKER_COMPONENT_PATTERN = re.compile(
    r"(?i)\b(Docker Desktop|com\.docker\.[A-Za-z0-9_.-]+|"
    r"com\.apple\.Virtualization\.VirtualMachine|vpnkit|vfkit|virtiofsd|"
    r"qemu-system-aarch64)\b"
)
DOCKER_PROCESS_MARKERS = (
    "docker",
    "com.docker",
    "vpnkit",
    "vfkit",
    "virtiofsd",
    "qemu-system",
    "com.apple.virtualization.virtualmachine",
)
LOG_SIGNAL_PATTERNS = {
    "host-oom": re.compile(r"(?i)out of memory|\boom\b|oom-kill|jetsam|memorystatus"),
    "memory-pressure": re.compile(r"(?i)memory pressure|application memory|low memory|highwater|memory limit"),
    "memory-leak-signal": re.compile(r"(?i)memory leak|leak suspected|unbounded memory"),
    "allocation-failure": re.compile(r"(?i)cannot allocate|allocation failed|mmap failed|resource exhausted"),
    "vm-crash": re.compile(r"(?i)(virtual machine|\bvm\b).{0,40}(crash|exit|terminated|stopped)|panic|segmentation fault"),
    "forced-exit": re.compile(r"(?i)killed process|signal 9|exit status 137|unexpected exit"),
    "watchdog-termination": re.compile(r"(?i)watchdog|watchdogd"),
}
LOGINWINDOW_SIZE_KINDS = (
    ("setting-rsize", re.compile(r"(?i)\bsetting\s+rsize\b")),
    ("app-size-string", re.compile(r"(?i)\bapp\s+size\s+string\b")),
)
LOGINWINDOW_SAMPLE_PATTERN = re.compile(
    r"(?i)\bSampling\s+App\s*:\s*Docker\s+Desktop\b"
)
LOGINWINDOW_RSIZE_PATTERN = re.compile(
    r"(?i)\bsetting\s+rsize\b\s*(?::|=|to)?\s*([0-9]{6,})\b"
)
LOGINWINDOW_CORRELATION_SECONDS = 60
ALLOWED_STATES = {"created", "running", "paused", "restarting", "removing", "exited", "dead"}
ALLOWED_PHASES = {"Pending", "Running", "Succeeded", "Failed", "Unknown"}
ALLOWED_REASONS = {
    "OOMKilled",
    "CrashLoopBackOff",
    "Error",
    "Completed",
    "ContainerCannotRun",
    "CreateContainerError",
    "CreateContainerConfigError",
    "ImagePullBackOff",
    "ErrImagePull",
    "Evicted",
    "BackOff",
    "Failed",
    "Unhealthy",
    "FailedScheduling",
    "FailedMount",
    "FailedAttachVolume",
    "NodeNotReady",
    "Killing",
}
DIAGNOSTIC_NAME_MARKERS = (
    "jetsamevent",
    "memory_resource",
    "resourceexception",
    "docker",
    "com.docker",
    "virtualization",
)
DIAGNOSTIC_EXTENSIONS = (".ips", ".diag")


class DiagnosticError(RuntimeError):
    """A secret-free diagnostic control failure."""


@dataclass(frozen=True)
class CommandResult:
    status: str
    output: str = ""


def _process_group_exists(group_id: int) -> bool:
    try:
        os.killpg(group_id, 0)
        return True
    except ProcessLookupError:
        return False
    except PermissionError:
        return True


def _terminate_probe_group(process: subprocess.Popen[bytes]) -> None:
    group_id = process.pid
    for requested_signal in (signal.SIGTERM, signal.SIGKILL):
        if not _process_group_exists(group_id):
            break
        try:
            os.killpg(group_id, requested_signal)
        except (ProcessLookupError, OSError):
            pass
        deadline = time.monotonic() + 0.5
        while time.monotonic() < deadline:
            process.poll()
            if not _process_group_exists(group_id):
                break
            time.sleep(0.01)
    if process.poll() is None:
        try:
            process.kill()
        except OSError:
            pass
    try:
        process.wait(timeout=0.5)
    except subprocess.TimeoutExpired:
        pass


def _command_environment() -> dict[str, str] | None:
    home = os.environ.get("HOME", "")
    if not os.path.isabs(home) or "\x00" in home or "\n" in home:
        return None
    return {
        "PATH": "/usr/bin:/bin:/usr/sbin:/sbin",
        "HOME": home,
        "LANG": "C",
        "LC_ALL": "C",
    }


def _run_fixed(
    command: Sequence[str],
    *,
    timeout: int = DEFAULT_TIMEOUT_SECONDS,
    max_capture_bytes: int = MAX_CAPTURE_BYTES,
) -> CommandResult:
    environment = _command_environment()
    if environment is None:
        return CommandResult("unavailable")
    try:
        process = subprocess.Popen(
            tuple(command),
            stdin=subprocess.DEVNULL,
            stdout=subprocess.PIPE,
            stderr=subprocess.DEVNULL,
            start_new_session=True,
            env=environment,
        )
    except OSError:
        return CommandResult("unavailable")

    assert process.stdout is not None
    selector = selectors.DefaultSelector()
    captured = bytearray()
    deadline = time.monotonic() + timeout
    reached_eof = False
    try:
        os.set_blocking(process.stdout.fileno(), False)
        selector.register(process.stdout, selectors.EVENT_READ)
        while not reached_eof:
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                _terminate_probe_group(process)
                return CommandResult("timeout")
            for key, _mask in selector.select(timeout=min(remaining, 0.1)):
                while True:
                    capacity = max_capture_bytes - len(captured)
                    try:
                        chunk = os.read(key.fd, min(64 * 1024, max(1, capacity + 1)))
                    except BlockingIOError:
                        break
                    if not chunk:
                        reached_eof = True
                        try:
                            selector.unregister(process.stdout)
                        except (KeyError, ValueError):
                            pass
                        break
                    if len(chunk) > capacity:
                        _terminate_probe_group(process)
                        return CommandResult("oversize")
                    captured.extend(chunk)
            if process.poll() is not None and not selector.get_map():
                reached_eof = True
        try:
            return_code = process.wait(timeout=max(0.0, deadline - time.monotonic()))
        except subprocess.TimeoutExpired:
            _terminate_probe_group(process)
            return CommandResult("timeout")
        if return_code != 0:
            return CommandResult("command-failed")
        try:
            return CommandResult("ok", bytes(captured).decode("utf-8", errors="strict"))
        except UnicodeDecodeError:
            return CommandResult("invalid")
    except (OSError, ValueError):
        _terminate_probe_group(process)
        return CommandResult("unavailable")
    except BaseException:
        _terminate_probe_group(process)
        raise
    finally:
        selector.close()
        process.stdout.close()


def _safe_name(raw: Any) -> str:
    value = str(raw or "").strip().rstrip("/").rsplit("/", 1)[-1]
    value = LONG_IDENTIFIER.sub("id", value)
    value = SAFE_NAME.sub("-", value).strip("._-+")
    return value[:80] or "unknown"


def _is_docker_component(raw: Any) -> bool:
    value = str(raw or "").casefold()
    return any(marker in value for marker in DOCKER_PROCESS_MARKERS)


def _safe_process_identity(raw: Any) -> str:
    value = str(raw or "unknown")
    if _is_docker_component(value):
        return _safe_name(value)
    digest = hashlib.sha256(f"diagnostic-process:{value}".encode("utf-8", errors="replace")).hexdigest()[:10]
    return f"other-{digest}"


def _safe_coalition(raw: Any) -> str:
    digest = hashlib.sha256(f"diagnostic-coalition:{raw}".encode("utf-8", errors="replace")).hexdigest()[:10]
    return f"coalition-{digest}"


def _safe_diagnostic_reason(raw: Any) -> str:
    value = str(raw or "").casefold()
    for marker, category in (
        ("per-process-limit", "per-process-limit"),
        ("highwater", "highwater"),
        ("vm-pageshortage", "vm-page-shortage"),
        ("page shortage", "vm-page-shortage"),
        ("memory pressure", "memory-pressure"),
        ("idle", "idle-exit"),
        ("cpu", "cpu-limit"),
        ("kill", "killed"),
    ):
        if marker in value:
            return category
    return "other" if value else "none"


def _safe_timestamp(raw: Any) -> str | None:
    match = TIMESTAMP_PATTERN.search(str(raw or ""))
    return match.group(1).replace(" ", "T", 1) if match else None


def _timestamp_epoch(raw: Any) -> float | None:
    value = _safe_timestamp(raw)
    if value is None:
        return None
    try:
        parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
    except ValueError:
        return None
    if parsed.tzinfo is None:
        parsed = parsed.replace(tzinfo=timezone.utc)
    return parsed.timestamp()


def _parse_percent(raw: Any) -> float | None:
    match = PERCENT_PATTERN.match(str(raw or "").strip())
    if match is None:
        return None
    value = float(match.group(1))
    return round(value, 3) if 0 <= value <= 100000 else None


def _size_bytes(value: str, unit: str) -> int | None:
    numeric = float(value)
    multipliers = {
        "b": 1,
        "kb": 1000,
        "mb": 1000**2,
        "gb": 1000**3,
        "tb": 1000**4,
        "kib": 1024,
        "mib": 1024**2,
        "gib": 1024**3,
        "tib": 1024**4,
    }
    multiplier = multipliers.get(unit.casefold())
    if numeric < 0 or multiplier is None:
        return None
    return int(numeric * multiplier)


def _first_size(raw: Any) -> int | None:
    match = SIZE_PATTERN.search(str(raw or ""))
    return _size_bytes(match.group(1), match.group(2)) if match else None


def _memory_usage_and_limit(raw: Any) -> tuple[int | None, int | None]:
    matches = list(SIZE_PATTERN.finditer(str(raw or "")))
    usage = _size_bytes(matches[0].group(1), matches[0].group(2)) if matches else None
    limit = _size_bytes(matches[1].group(1), matches[1].group(2)) if len(matches) > 1 else None
    return usage, limit


def _json_lines(output: str) -> list[dict[str, Any]] | None:
    records: list[dict[str, Any]] = []
    for line in output.splitlines():
        if not line.strip():
            continue
        if len(records) >= MAX_RECORDS:
            return None
        try:
            value = json.loads(line)
        except json.JSONDecodeError:
            return None
        if not isinstance(value, dict):
            return None
        records.append(value)
    return records


def parse_desktop_version(output: str) -> str | None:
    value = output.strip()
    return value if VERSION_PATTERN.fullmatch(value) else None


def parse_os_version(output: str) -> str | None:
    value = output.strip()
    return value if re.fullmatch(r"[0-9]{1,3}(?:\.[0-9]{1,3}){1,3}", value) else None


def parse_os_build(output: str) -> str | None:
    value = output.strip()
    return value if OS_BUILD_PATTERN.fullmatch(value) else None


def parse_desktop_status(output: str) -> dict[str, str] | None:
    try:
        value = json.loads(output)
    except json.JSONDecodeError:
        return None
    if not isinstance(value, dict):
        return None
    allowed = {"running", "stopped", "starting", "stopping", "unavailable"}
    destinations = {
        "desktop": "desktop",
        "vm": "desktopVirtualMachine",
        "virtualmachine": "desktopVirtualMachine",
        "docker": "dockerEngine",
        "engine": "dockerEngine",
        "containerengine": "dockerEngine",
        "kubernetes": "kubernetesEngine",
    }
    result: dict[str, str] = {}
    root_status = _mapping_value(value, ("status",))
    if isinstance(root_status, str) and root_status.casefold() in allowed:
        result["desktop"] = root_status.casefold()
    for mapping in _walk_dicts(value):
        for raw_key, raw_value in mapping.items():
            destination = destinations.get(
                re.sub(r"[^a-z]", "", str(raw_key).casefold())
            )
            if destination is None:
                continue
            state = raw_value
            if isinstance(raw_value, dict):
                state = _mapping_value(raw_value, ("status", "state"))
            if isinstance(state, str) and state.casefold() in allowed:
                result.setdefault(destination, state.casefold())
    return result or None


def parse_docker_stats(output: str) -> dict[str, Any] | None:
    values = _json_lines(output)
    if values is None:
        return None
    records = []
    for value in values:
        memory, memory_limit = _memory_usage_and_limit(value.get("memory"))
        cpu = _parse_percent(value.get("cpu"))
        memory_percent = _parse_percent(value.get("memoryPercent"))
        try:
            pids = max(0, int(str(value.get("pids", "0"))))
        except ValueError:
            pids = 0
        records.append(
            {
                "workload": _safe_name(value.get("name")),
                "memoryUsageBytes": memory,
                "memoryLimitBytes": memory_limit,
                "memoryPercent": memory_percent,
                "cpuPercent": cpu,
                "processCount": min(pids, 1_000_000),
            }
        )
    records.sort(key=lambda item: (-(item.get("memoryUsageBytes") or 0), item["workload"]))
    return {
        "containerCount": len(records),
        "totalMemoryUsageBytes": sum(item.get("memoryUsageBytes") or 0 for item in records),
        "topMemoryWorkloads": records[:TOP_RECORDS],
    }


def parse_docker_ps(output: str) -> dict[str, Any] | None:
    values = _json_lines(output)
    if values is None:
        return None
    states: Counter[str] = Counter()
    workloads = []
    for value in values:
        raw_state = str(value.get("state", "")).casefold()
        state = raw_state if raw_state in ALLOWED_STATES else "unknown"
        states[state] += 1
        record = {"name": _safe_name(value.get("name")), "state": state}
        for source, destination in (
            ("project", "composeProject"),
            ("service", "composeService"),
            ("k8sContainer", "kubernetesContainer"),
            ("k8sPod", "kubernetesPod"),
            ("k8sNamespace", "kubernetesNamespace"),
        ):
            if value.get(source):
                record[destination] = _safe_name(value[source])
        workloads.append(record)
    workloads.sort(key=lambda item: (item["state"], item["name"]))
    return {
        "containerCount": len(workloads),
        "stateCounts": dict(sorted(states.items())),
        "workloads": workloads[:MAX_RECORDS],
    }


def _safe_reason(raw: str) -> str:
    value = raw.strip()
    return value if value in ALLOWED_REASONS else ("other" if value else "none")


def parse_kubernetes_pods(output: str) -> dict[str, Any] | None:
    phases: Counter[str] = Counter()
    restarted = []
    pod_count = 0
    for line in output.splitlines():
        if not line.strip():
            continue
        if pod_count >= MAX_RECORDS:
            return None
        fields = line.split("\t", 3)
        if len(fields) != 4:
            return None
        namespace, pod, raw_phase, statuses = fields
        phase = raw_phase if raw_phase in ALLOWED_PHASES else "Unknown"
        phases[phase] += 1
        pod_count += 1
        total_restarts = 0
        reasons: Counter[str] = Counter()
        containers = []
        termination_times = []
        for raw_status in statuses.split(";"):
            if not raw_status:
                continue
            parts = raw_status.split(",", 4)
            if len(parts) != 5:
                continue
            name, restart_value, waiting_reason, last_reason, finished_at = parts
            try:
                restart_count = max(0, int(restart_value))
            except ValueError:
                restart_count = 0
            total_restarts += restart_count
            for reason in (waiting_reason, last_reason):
                safe_reason = _safe_reason(reason)
                if safe_reason != "none":
                    reasons[safe_reason] += 1
            if restart_count or reasons:
                containers.append(
                    {"name": _safe_name(name), "restartCount": restart_count}
                )
            safe_finished_at = _safe_timestamp(finished_at)
            if safe_finished_at:
                termination_times.append(safe_finished_at)
        if total_restarts or reasons or phase in {"Failed", "Unknown"}:
            restarted.append(
                {
                    "namespace": _safe_name(namespace),
                    "pod": _safe_name(pod),
                    "phase": phase,
                    "restartCount": total_restarts,
                    "reasons": dict(sorted(reasons.items())),
                    "containers": containers[:16],
                    "latestTerminationTime": max(termination_times) if termination_times else None,
                }
            )
    restarted.sort(key=lambda item: (-item["restartCount"], item["namespace"], item["pod"]))
    return {
        "podCount": pod_count,
        "phaseCounts": dict(sorted(phases.items())),
        "topRestartedPods": restarted[:TOP_RECORDS],
    }


def parse_kubernetes_events(output: str) -> dict[str, Any] | None:
    reasons: Counter[str] = Counter()
    records = []
    for line in output.splitlines():
        if not line.strip():
            continue
        if len(records) >= MAX_RECORDS:
            return None
        fields = line.split(None, 5)
        if len(fields) != 6:
            return None
        namespace, raw_kind, name, raw_reason, raw_count, raw_timestamp = fields
        try:
            count = min(max(1, int(raw_count)), 1_000_000_000)
        except ValueError:
            count = 1
        reason = _safe_reason(raw_reason)
        reasons[reason] += count
        kind = raw_kind if raw_kind in {
            "Pod", "Node", "Job", "CronJob", "Deployment", "ReplicaSet", "PersistentVolumeClaim",
        } else "Other"
        records.append(
            {
                "namespace": _safe_name(namespace),
                "kind": kind,
                "workload": _safe_name(name),
                "reason": reason,
                "count": count,
                "lastTimestamp": _safe_timestamp(raw_timestamp),
            }
        )
    records.sort(key=lambda item: (-item["count"], item["namespace"], item["workload"]))
    return {"reasonCounts": dict(sorted(reasons.items())), "topWarnings": records[:TOP_RECORDS]}


def parse_docker_processes(output: str) -> dict[str, Any] | None:
    groups: dict[str, dict[str, Any]] = {}
    for line in output.splitlines():
        fields = line.strip().split(None, 3)
        if len(fields) != 4 or not fields[0].isdigit() or not fields[1].isdigit():
            continue
        elapsed = _parse_elapsed_seconds(fields[2])
        if elapsed is None:
            continue
        name = _safe_name(fields[3])
        if not any(marker in name.casefold() for marker in DOCKER_PROCESS_MARKERS):
            continue
        group = groups.setdefault(
            name,
            {
                "component": name,
                "count": 0,
                "rssBytes": 0,
                "virtualBytes": 0,
                "oldestElapsedSeconds": 0,
                "newestElapsedSeconds": elapsed,
            },
        )
        group["count"] += 1
        group["rssBytes"] += int(fields[0]) * 1024
        group["virtualBytes"] += int(fields[1]) * 1024
        group["oldestElapsedSeconds"] = max(group["oldestElapsedSeconds"], elapsed)
        group["newestElapsedSeconds"] = min(group["newestElapsedSeconds"], elapsed)
    if not groups:
        return {"processCount": 0, "totalRssBytes": 0, "components": []}
    ordered = sorted(groups.values(), key=lambda item: (-item["rssBytes"], item["component"]))
    return {
        "processCount": sum(item["count"] for item in ordered),
        "totalRssBytes": sum(item["rssBytes"] for item in ordered),
        "components": ordered[:TOP_RECORDS],
    }


def _parse_elapsed_seconds(raw: str) -> int | None:
    match = re.fullmatch(r"(?:(\d+)-)?(?:(\d{1,2}):)?(\d{1,2}):(\d{2})", raw)
    if match is None:
        return None
    days = int(match.group(1) or 0)
    hours = int(match.group(2) or 0)
    minutes = int(match.group(3))
    seconds = int(match.group(4))
    if hours > 23 or minutes > 59 or seconds > 59:
        return None
    return days * 86400 + hours * 3600 + minutes * 60 + seconds


def _log_signals(lines: Sequence[tuple[str | None, str, str]]) -> dict[str, Any]:
    categories: Counter[str] = Counter()
    components: dict[str, dict[str, Any]] = {}
    latest: str | None = None
    matched_lines = 0
    max_reported = 0
    signal_times = set()
    for timestamp, source, message in lines:
        matched_categories = [name for name, pattern in LOG_SIGNAL_PATTERNS.items() if pattern.search(message)]
        if not matched_categories:
            continue
        matched_lines += 1
        categories.update(matched_categories)
        if timestamp and (latest is None or timestamp > latest):
            latest = timestamp
        if timestamp:
            signal_times.add(timestamp)
        sizes = [_size_bytes(match.group(1), match.group(2)) or 0 for match in SIZE_PATTERN.finditer(message)]
        line_peak = max(sizes, default=0)
        max_reported = max(max_reported, line_peak)
        names = {_safe_name(match.group(1)) for match in DOCKER_COMPONENT_PATTERN.finditer(message)}
        if any(marker in source.casefold() for marker in DOCKER_PROCESS_MARKERS):
            names.add(_safe_name(source))
        for name in names:
            component = components.setdefault(name, {"component": name, "signalCount": 0, "maxReportedBytes": 0})
            component["signalCount"] += 1
            component["maxReportedBytes"] = max(component["maxReportedBytes"], line_peak)
    ordered = sorted(components.values(), key=lambda item: (-item["maxReportedBytes"], -item["signalCount"], item["component"]))
    return {
        "matchedLineCount": matched_lines,
        "categoryCounts": dict(sorted(categories.items())),
        "latestSignalTime": latest,
        "signalTimes": sorted(signal_times)[-64:],
        "maxReportedBytes": max_reported,
        "components": ordered[:TOP_RECORDS],
        "applicationSizeEvidence": [],
    }


def parse_desktop_logs(output: str) -> dict[str, Any]:
    lines = []
    for raw_line in output.splitlines():
        lines.append((_safe_timestamp(raw_line), "docker-desktop", raw_line))
    return _log_signals(lines)


def _unified_log_lines(output: str) -> list[tuple[str | None, str, str]] | None:
    lines = []
    for raw_line in output.splitlines():
        if not raw_line.strip():
            continue
        if len(lines) >= 50_000:
            return None
        try:
            value = json.loads(raw_line)
        except json.JSONDecodeError:
            return None
        if not isinstance(value, dict):
            return None
        message = value.get("eventMessage")
        if not isinstance(message, str):
            continue
        source = value.get("process") if isinstance(value.get("process"), str) else "macos"
        lines.append((_safe_timestamp(value.get("timestamp")), _safe_name(source), message))
    return lines


def parse_unified_logs(output: str) -> dict[str, Any] | None:
    lines = _unified_log_lines(output)
    return _log_signals(lines) if lines is not None else None


def parse_loginwindow_logs(output: str) -> dict[str, Any] | None:
    lines = _unified_log_lines(output)
    if lines is None:
        return None
    result = _log_signals(lines)
    evidence = []
    sample_timestamp: str | None = None
    sample_epoch: float | None = None
    for timestamp, _source, message in lines:
        if re.search(r"(?i)\bSampling\s+App\s*:", message):
            if LOGINWINDOW_SAMPLE_PATTERN.search(message):
                sample_timestamp = timestamp
                sample_epoch = _timestamp_epoch(timestamp)
            else:
                sample_timestamp = None
                sample_epoch = None
            continue
        measurement_epoch = _timestamp_epoch(timestamp)
        if sample_epoch is None or measurement_epoch is None:
            continue
        delta = measurement_epoch - sample_epoch
        if delta < 0 or delta > LOGINWINDOW_CORRELATION_SECONDS:
            continue
        kind = None
        reported_bytes = None
        rsize_match = LOGINWINDOW_RSIZE_PATTERN.search(message)
        if rsize_match:
            kind = "setting-rsize"
            reported_bytes = _nonnegative_integer(rsize_match.group(1))
        elif re.search(r"(?i)\bapp\s+size\s+string\b", message):
            sizes = [
                _size_bytes(match.group(1), match.group(2)) or 0
                for match in SIZE_PATTERN.finditer(message)
            ]
            if sizes:
                kind = "app-size-string"
                reported_bytes = max(sizes)
        if kind and reported_bytes:
            evidence.append(
                {
                    "kind": kind,
                    "component": "Docker-Desktop",
                    "reportedBytes": reported_bytes,
                    "samplingTime": sample_timestamp,
                    "measurementTime": timestamp,
                    "deltaSeconds": int(delta),
                }
            )
    result["applicationSizeEvidence"] = sorted(
        evidence,
        key=lambda item: (-item["reportedBytes"], item.get("measurementTime") or ""),
    )[:32]
    if evidence:
        result["maxReportedBytes"] = max(
            result.get("maxReportedBytes", 0),
            max(item["reportedBytes"] for item in evidence),
        )
    return result


def _json_documents(raw: str) -> list[Any]:
    decoder = json.JSONDecoder()
    documents = []
    index = 0
    while index < len(raw) and len(documents) < 4:
        while index < len(raw) and raw[index].isspace():
            index += 1
        if index >= len(raw):
            break
        try:
            value, index = decoder.raw_decode(raw, index)
        except json.JSONDecodeError:
            return []
        documents.append(value)
    return documents


def _walk_dicts(value: Any, *, depth: int = 0):
    if depth > 10:
        return
    if isinstance(value, dict):
        yield value
        for child in value.values():
            yield from _walk_dicts(child, depth=depth + 1)
    elif isinstance(value, list):
        for child in value[:10_000]:
            yield from _walk_dicts(child, depth=depth + 1)


def _first_value(documents: Sequence[Any], keys: Sequence[str]) -> Any:
    folded = {key.casefold() for key in keys}
    for document in documents:
        for mapping in _walk_dicts(document):
            for key, value in mapping.items():
                if str(key).casefold() in folded and not isinstance(value, (dict, list)):
                    return value
    return None


def _nonnegative_integer(value: Any) -> int | None:
    if isinstance(value, bool):
        return None
    try:
        parsed = int(value)
    except (TypeError, ValueError, OverflowError):
        return None
    return parsed if 0 <= parsed <= 2**63 - 1 else None


def _find_process_records(documents: Sequence[Any]) -> list[dict[str, Any]]:
    for document in documents:
        for mapping in _walk_dicts(document):
            for key, value in mapping.items():
                if str(key).casefold() == "processes" and isinstance(value, list):
                    records = [item for item in value if isinstance(item, dict)]
                    if records:
                        return records[:10_000]
    return []


def _mapping_value(mapping: Mapping[str, Any], keys: Sequence[str]) -> Any:
    folded = {key.casefold() for key in keys}
    for key, value in mapping.items():
        if str(key).casefold() in folded:
            return value
    return None


def _diagnostic_kind(file_name: str) -> str:
    folded = file_name.casefold()
    if "jetsamevent" in folded:
        return "jetsam"
    if "memory_resource" in folded or "resourceexception" in folded:
        return "memory-resource"
    if "virtualization" in folded:
        return "virtualization"
    return "docker-component"


def _diagnostic_snapshot(raw: str, *, file_name: str, modified_at: float) -> dict[str, Any] | None:
    documents = _json_documents(raw)
    if not documents:
        return None
    page_size = _nonnegative_integer(_first_value(documents, ("pageSize", "page_size"))) or 4096
    if page_size not in (4096, 16384, 65536):
        page_size = 4096
    raw_largest = _first_value(documents, ("largestProcess", "largest_process"))
    process_records = _find_process_records(documents)
    processes = []
    coalition_totals: dict[str, dict[str, Any]] = {}
    raw_name_to_current: dict[str, int] = {}
    for record in process_records:
        raw_name = _mapping_value(record, ("name", "procName", "processName", "process"))
        if raw_name is None:
            continue
        rpages_value = _nonnegative_integer(_mapping_value(record, ("rpages", "residentPages")))
        footprint = _nonnegative_integer(
            _mapping_value(record, ("physicalFootprint", "phys_footprint", "footprint", "residentSize"))
        )
        lifetime_pages = _nonnegative_integer(_mapping_value(record, ("lifetimeMax", "lifetime_max")))
        current_bytes = rpages_value * page_size if rpages_value is not None else (footprint or 0)
        lifetime_bytes = (
            lifetime_pages * page_size
            if rpages_value is not None and lifetime_pages is not None
            else lifetime_pages
        )
        coalition_raw = _mapping_value(record, ("coalition", "coalitionID", "coalition_id"))
        coalition = _safe_coalition(coalition_raw) if coalition_raw is not None else "coalition-none"
        identity = _safe_process_identity(raw_name)
        raw_name_to_current[str(raw_name)] = current_bytes
        process = {
            "process": identity,
            "currentBytes": current_bytes,
            "lifetimeMaxBytes": lifetime_bytes,
            "coalition": coalition,
            "reason": _safe_diagnostic_reason(_mapping_value(record, ("reason", "killReason"))),
            "state": _safe_diagnostic_reason(_mapping_value(record, ("states", "state"))),
            "dockerComponent": _is_docker_component(raw_name),
        }
        processes.append(process)
        group = coalition_totals.setdefault(
            coalition,
            {
                "coalition": coalition,
                "currentBytes": 0,
                "dockerNamedProcessCurrentBytes": 0,
                "processCount": 0,
            },
        )
        group["currentBytes"] += current_bytes
        group["processCount"] += 1
        if process["dockerComponent"]:
            group["dockerNamedProcessCurrentBytes"] += current_bytes

    if not processes:
        raw_name = _first_value(documents, ("procName", "processName", "app_name", "process"))
        footprint = _nonnegative_integer(
            _first_value(documents, ("physicalFootprint", "phys_footprint", "footprint", "residentSize"))
        )
        lifetime = _nonnegative_integer(_first_value(documents, ("lifetimeMax", "lifetime_max")))
        if raw_name is not None or footprint is not None or lifetime is not None:
            processes.append(
                {
                    "process": _safe_process_identity(raw_name),
                    "currentBytes": footprint,
                    "lifetimeMaxBytes": lifetime,
                    "coalition": "coalition-none",
                    "reason": _safe_diagnostic_reason(_first_value(documents, ("reason", "exceptionType"))),
                    "state": "none",
                    "dockerComponent": _is_docker_component(raw_name),
                }
            )

    processes.sort(key=lambda item: (-(item.get("currentBytes") or 0), item["process"]))
    docker_processes = [item for item in processes if item["dockerComponent"]]
    selected = processes[:TOP_RECORDS]
    for process in docker_processes:
        if process not in selected and len(selected) < TOP_RECORDS * 2:
            selected.append(process)
    coalitions = sorted(
        coalition_totals.values(),
        key=lambda item: (
            -item["dockerNamedProcessCurrentBytes"],
            -item["currentBytes"],
            item["coalition"],
        ),
    )[:TOP_RECORDS]
    docker_containing_coalitions = [
        item for item in coalitions if item["dockerNamedProcessCurrentBytes"] > 0
    ]
    timestamp = _safe_timestamp(_first_value(documents, ("timestamp", "captureTime", "date")))
    if timestamp is None:
        timestamp = time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime(modified_at))
    largest = _safe_process_identity(raw_largest) if raw_largest is not None else (processes[0]["process"] if processes else "unknown")
    largest_current = raw_name_to_current.get(str(raw_largest)) if raw_largest is not None else (processes[0].get("currentBytes") if processes else None)
    return {
        "kind": _diagnostic_kind(file_name),
        "timestamp": timestamp,
        "pageSizeBytes": page_size,
        "largestProcess": largest,
        "largestProcessCurrentBytes": largest_current,
        "dockerNamedProcessCurrentBytes": sum(
            item.get("currentBytes") or 0 for item in docker_processes
        ),
        "processes": selected,
        "coalitions": coalitions,
        "dockerContainingCoalitions": docker_containing_coalitions,
    }


def collect_diagnostic_reports() -> dict[str, Any]:
    home = Path(os.environ.get("HOME", "/nonexistent"))
    roots = (
        home / "Library/Logs/DiagnosticReports",
        home / "Library/Logs/DiagnosticReports/Retired",
        Path("/Library/Logs/DiagnosticReports"),
        Path("/Library/Logs/DiagnosticReports/Retired"),
    )
    cutoff = time.time() - DIAGNOSTIC_LOOKBACK_SECONDS
    candidates: list[tuple[float, str, str]] = []
    accessible_roots = 0
    eligible_candidate_count = 0
    truncated_root_count = 0
    for root in roots:
        try:
            entries = os.scandir(root)
        except OSError:
            continue
        accessible_roots += 1
        with entries:
            for entry_index, entry in enumerate(entries):
                if entry_index >= DIAGNOSTIC_ENTRY_LIMIT_PER_ROOT:
                    truncated_root_count += 1
                    break
                folded = entry.name.casefold()
                if not folded.endswith(DIAGNOSTIC_EXTENSIONS) or not any(marker in folded for marker in DIAGNOSTIC_NAME_MARKERS):
                    continue
                try:
                    metadata = entry.stat(follow_symlinks=False)
                except OSError:
                    continue
                if not stat.S_ISREG(metadata.st_mode) or metadata.st_mtime < cutoff or metadata.st_size > DIAGNOSTIC_FILE_BYTES:
                    continue
                eligible_candidate_count += 1
                candidate = (metadata.st_mtime, entry.path, entry.name)
                if len(candidates) < DIAGNOSTIC_CANDIDATE_POOL_LIMIT:
                    heapq.heappush(candidates, candidate)
                elif candidate > candidates[0]:
                    heapq.heapreplace(candidates, candidate)
    candidates.sort(reverse=True)
    snapshots = []
    total_bytes = 0
    for modified_at, raw_path, name in candidates[:DIAGNOSTIC_FILE_LIMIT]:
        try:
            descriptor = os.open(raw_path, os.O_RDONLY | os.O_NONBLOCK | getattr(os, "O_NOFOLLOW", 0))
        except OSError:
            continue
        try:
            metadata = os.fstat(descriptor)
            if not stat.S_ISREG(metadata.st_mode) or metadata.st_size > DIAGNOSTIC_FILE_BYTES:
                continue
            if total_bytes + metadata.st_size > DIAGNOSTIC_TOTAL_BYTES:
                break
            chunks = []
            remaining = metadata.st_size + 1
            while remaining > 0:
                chunk = os.read(descriptor, min(64 * 1024, remaining))
                if not chunk:
                    break
                chunks.append(chunk)
                remaining -= len(chunk)
            raw_bytes = b"".join(chunks)
            if len(raw_bytes) > DIAGNOSTIC_FILE_BYTES:
                continue
            total_bytes += len(raw_bytes)
            try:
                raw = raw_bytes.decode("utf-8", errors="strict")
            except UnicodeDecodeError:
                continue
            snapshot = _diagnostic_snapshot(raw, file_name=name, modified_at=modified_at)
            if snapshot is not None:
                snapshots.append(snapshot)
        finally:
            os.close(descriptor)
    snapshots.sort(key=lambda item: item["timestamp"], reverse=True)
    return {
        "accessibleRootCount": accessible_roots,
        "eligibleCandidateCount": eligible_candidate_count,
        "retainedCandidateCount": len(candidates),
        "inspectedCandidateCount": min(len(candidates), DIAGNOSTIC_FILE_LIMIT),
        "truncatedRootCount": truncated_root_count,
        "parsedSnapshotCount": len(snapshots),
        "snapshots": snapshots[:TOP_RECORDS],
    }


def parse_diagnostic_reports_child(output: str) -> dict[str, Any] | None:
    try:
        value = json.loads(output)
    except json.JSONDecodeError:
        return None
    if not isinstance(value, dict) or value.get("mode") != "sanitized-diagnostic-reports":
        return None
    result = value.get("result")
    return result if isinstance(result, dict) else None


def _add_incident_correlations(report: dict[str, Any]) -> None:
    diagnostic_reports = report.get("diagnosticReports")
    if not isinstance(diagnostic_reports, dict):
        return
    candidates: list[tuple[float, str, str]] = []
    for key, source in (
        ("desktopLogsCurrentBoot", "desktop-current-boot"),
        ("desktopLogsPreviousBoot", "desktop-previous-boot"),
    ):
        section = report.get(key)
        if not isinstance(section, dict):
            continue
        for timestamp in section.get("signalTimes", []):
            epoch = _timestamp_epoch(timestamp)
            if epoch is not None:
                candidates.append((epoch, source, timestamp))
    pods = report.get("kubernetesPods")
    if isinstance(pods, dict):
        for pod in pods.get("topRestartedPods", []):
            if not isinstance(pod, dict):
                continue
            timestamp = pod.get("latestTerminationTime")
            epoch = _timestamp_epoch(timestamp)
            if epoch is not None:
                candidates.append((epoch, "kubernetes-termination", str(timestamp)))
    events = report.get("kubernetesEvents")
    if isinstance(events, dict):
        for event in events.get("topWarnings", []):
            if not isinstance(event, dict):
                continue
            timestamp = event.get("lastTimestamp")
            epoch = _timestamp_epoch(timestamp)
            if epoch is not None:
                candidates.append((epoch, "kubernetes-warning", str(timestamp)))
    correlations = []
    for snapshot in diagnostic_reports.get("snapshots", [])[:TOP_RECORDS]:
        if not isinstance(snapshot, dict):
            continue
        incident_time = snapshot.get("timestamp")
        incident_epoch = _timestamp_epoch(incident_time)
        if incident_epoch is None or not candidates:
            continue
        nearest = min(candidates, key=lambda item: abs(item[0] - incident_epoch))
        correlations.append(
            {
                "incidentTime": incident_time,
                "nearestChurnTime": nearest[2],
                "source": nearest[1],
                "absoluteDeltaSeconds": int(abs(nearest[0] - incident_epoch)),
            }
        )
    if correlations:
        report["incidentCorrelations"] = correlations


def _record_probe(
    report: dict[str, Any],
    name: str,
    command: Sequence[str],
    parser: Callable[[str], Any],
    destination: str,
    *,
    timeout: int = DEFAULT_TIMEOUT_SECONDS,
    max_capture_bytes: int = MAX_CAPTURE_BYTES,
) -> None:
    print(f"docker-rca stage={name} status=start", flush=True)
    result = _run_fixed(command, timeout=timeout, max_capture_bytes=max_capture_bytes)
    try:
        value = parser(result.output) if result.status == "ok" else None
    except (ValueError, TypeError, OverflowError, json.JSONDecodeError):
        value = None
    status = "ok" if result.status == "ok" and value is not None else result.status
    if result.status == "ok" and value is None:
        status = "invalid"
    report["probes"][name] = status
    if status == "ok":
        report[destination] = value
    print(f"docker-rca stage={name} status={status}", flush=True)


def collect_report() -> dict[str, Any]:
    report: dict[str, Any] = {
        "mode": "docker-rca",
        "schemaVersion": 1,
        "readOnly": True,
        "lookbackHours": LOOKBACK_HOURS,
        "probes": {},
    }
    _record_probe(report, "macos-version", ("/usr/bin/sw_vers", "-productVersion"), parse_os_version, "macosVersion", timeout=8)
    _record_probe(report, "macos-build", ("/usr/bin/sw_vers", "-buildVersion"), parse_os_build, "macosBuild", timeout=8)
    _record_probe(report, "desktop-app-version", ("/usr/libexec/PlistBuddy", "-c", "Print :CFBundleShortVersionString", "/Applications/Docker.app/Contents/Info.plist"), parse_desktop_version, "desktopVersion", timeout=8)
    _record_probe(report, "desktop-cli-version", (DOCKER_CLI, "desktop", "version", "--short"), parse_desktop_version, "desktopCliVersion", timeout=8)
    _record_probe(report, "desktop-status", (DOCKER_CLI, "desktop", "status", "--format", "json"), parse_desktop_status, "desktopStatus", timeout=8)
    _record_probe(report, "docker-processes", ("/bin/ps", "-axww", "-o", "rss=", "-o", "vsz=", "-o", "etime=", "-o", "comm="), parse_docker_processes, "dockerProcesses", timeout=8)
    _record_probe(report, "docker-stats", (DOCKER_CLI, "--context", "docker-desktop", "stats", "--no-stream", "--format", STATS_FORMAT), parse_docker_stats, "dockerStats")
    _record_probe(report, "docker-workloads", (DOCKER_CLI, "--context", "docker-desktop", "ps", "--all", "--format", PS_FORMAT), parse_docker_ps, "dockerWorkloads")
    _record_probe(report, "kubernetes-pods", (KUBECTL, "--context", "docker-desktop", "get", "pods", "--all-namespaces", "--request-timeout=10s", "-o", f"jsonpath={POD_JSONPATH}"), parse_kubernetes_pods, "kubernetesPods")
    _record_probe(report, "kubernetes-warning-events", (KUBECTL, "--context", "docker-desktop", "get", "events", "--all-namespaces", "--request-timeout=10s", "--field-selector", "type=Warning", "--no-headers", "-o", EVENT_COLUMNS), parse_kubernetes_events, "kubernetesEvents")
    lookback = f"-{LOOKBACK_HOURS}h"
    _record_probe(report, "desktop-logs-current-boot", (DOCKER_CLI, "desktop", "logs", "-S", lookback, "-p", "0"), parse_desktop_logs, "desktopLogsCurrentBoot", timeout=LOG_TIMEOUT_SECONDS, max_capture_bytes=LOG_CAPTURE_BYTES)
    _record_probe(report, "desktop-logs-previous-boot", (DOCKER_CLI, "desktop", "logs", "-b", "1", "-S", lookback, "-p", "0"), parse_desktop_logs, "desktopLogsPreviousBoot", timeout=LOG_TIMEOUT_SECONDS, max_capture_bytes=LOG_CAPTURE_BYTES)
    _record_probe(report, "macos-loginwindow-app-memory", ("/usr/bin/log", "show", "--last", f"{LOOKBACK_HOURS}h", "--style", "ndjson", "--info", "--predicate", UNIFIED_LOGINWINDOW_PREDICATE), parse_loginwindow_logs, "macosLoginwindowEvents", timeout=15, max_capture_bytes=LOG_CAPTURE_BYTES)
    _record_probe(report, "macos-system-memory", ("/usr/bin/log", "show", "--last", f"{LOOKBACK_HOURS}h", "--style", "ndjson", "--info", "--predicate", UNIFIED_SYSTEM_MEMORY_PREDICATE), parse_unified_logs, "macosSystemMemoryEvents", timeout=UNIFIED_LOG_TIMEOUT_SECONDS, max_capture_bytes=UNIFIED_LOG_CAPTURE_BYTES)
    _record_probe(
        report,
        "diagnostic-reports",
        (sys.executable, os.path.realpath(__file__), "diagnostic-reports-child"),
        parse_diagnostic_reports_child,
        "diagnosticReports",
        timeout=20,
        max_capture_bytes=DIAGNOSTIC_CHILD_CAPTURE_BYTES,
    )
    _add_incident_correlations(report)
    return report


def _format_bytes(value: Any) -> str:
    if not isinstance(value, int) or isinstance(value, bool) or value < 0:
        return "unavailable"
    if value >= 1024**3:
        return f"{value / 1024**3:.2f} GiB"
    return f"{value / 1024**2:.1f} MiB"


def _summary_records(title: str, records: Any, fields: Sequence[tuple[str, str]]) -> list[str]:
    lines = [title]
    if not isinstance(records, list) or not records:
        return [*lines, "  - none observed"]
    for record in records[:TOP_RECORDS]:
        if not isinstance(record, dict):
            continue
        parts = [f"{label} `{record.get(key, 'unavailable')}`" for key, label in fields]
        lines.append("  - " + "; ".join(parts))
    return lines if len(lines) > 1 else [*lines, "  - none observed"]


def render_summary(report: Mapping[str, Any], workflow_status: str) -> str:
    if report.get("mode") != "docker-rca":
        return "\n".join(("## MacBook Air Docker RCA snapshot", "", f"- Workflow status: `{workflow_status}`", "- Snapshot: `not submitted`", "- No runtime mutation was attempted.", ""))
    probes = report.get("probes") if isinstance(report.get("probes"), dict) else {}
    processes = report.get("dockerProcesses") if isinstance(report.get("dockerProcesses"), dict) else {}
    stats = report.get("dockerStats") if isinstance(report.get("dockerStats"), dict) else {}
    pods = report.get("kubernetesPods") if isinstance(report.get("kubernetesPods"), dict) else {}
    events = report.get("kubernetesEvents") if isinstance(report.get("kubernetesEvents"), dict) else {}
    diagnostics = report.get("diagnosticReports") if isinstance(report.get("diagnosticReports"), dict) else {}
    desktop_status = report.get("desktopStatus") if isinstance(report.get("desktopStatus"), dict) else {}
    log_sections = [
        ("macOS loginwindow application-memory evidence", report.get("macosLoginwindowEvents")),
        ("macOS system memory events", report.get("macosSystemMemoryEvents")),
        ("Desktop current boot", report.get("desktopLogsCurrentBoot")),
        ("Desktop previous boot", report.get("desktopLogsPreviousBoot")),
    ]
    lines = [
        "## MacBook Air Docker RCA snapshot",
        "",
        f"- Workflow status: `{workflow_status}`",
        f"- Snapshot: `{'submitted' if workflow_status == 'success' else 'partial'}` (probe availability is not a runtime health gate)",
        f"- Read-only probes: `{sum(value == 'ok' for value in probes.values())}/{len(probes)}` completed",
        f"- macOS: `{report.get('macosVersion', 'unavailable')}` build `{report.get('macosBuild', 'unavailable')}`",
        f"- Docker Desktop version: `{report.get('desktopVersion', 'unavailable')}`",
        f"- Docker Desktop CLI plugin version: `{report.get('desktopCliVersion', 'unavailable')}`",
        f"- Desktop status: `{desktop_status.get('desktop', 'unavailable')}`; Docker engine `{desktop_status.get('dockerEngine', 'unavailable')}`; Kubernetes `{desktop_status.get('kubernetesEngine', 'unavailable')}`",
        f"- Docker host processes: `{processes.get('processCount', 'unavailable')}`; resident total `{_format_bytes(processes.get('totalRssBytes'))}`",
        f"- Container memory total: `{_format_bytes(stats.get('totalMemoryUsageBytes'))}` across `{stats.get('containerCount', 'unavailable')}` running containers",
        f"- Kubernetes pods: `{pods.get('podCount', 'unavailable')}`; phases `{json.dumps(pods.get('phaseCounts', {}), sort_keys=True)}`",
        f"- Kubernetes warning reasons: `{json.dumps(events.get('reasonCounts', {}), sort_keys=True)}`",
        f"- Diagnostic reports: `{diagnostics.get('parsedSnapshotCount', 'unavailable')}` parsed from `{diagnostics.get('inspectedCandidateCount', 'unavailable')}` inspected / `{diagnostics.get('eligibleCandidateCount', 'unavailable')}` eligible bounded recent candidates; roots accessible `{diagnostics.get('accessibleRootCount', 'unavailable')}/4`; truncated roots `{diagnostics.get('truncatedRootCount', 'unavailable')}`",
        "- A zero DiagnosticReports count means no matching readable artifact was captured by this bounded probe; it is not evidence that no OOM occurred.",
    ]
    lines.extend(_summary_records("- Docker component RSS/virtual/lifetime evidence:", processes.get("components"), (("component", "component"), ("rssBytes", "RSS bytes"), ("virtualBytes", "virtual bytes"), ("oldestElapsedSeconds", "oldest elapsed seconds"), ("newestElapsedSeconds", "newest elapsed seconds"))))
    lines.extend(_summary_records("- Highest container memory:", stats.get("topMemoryWorkloads"), (("workload", "workload"), ("memoryUsageBytes", "memory bytes"), ("memoryLimitBytes", "limit bytes"), ("memoryPercent", "memory %"), ("cpuPercent", "CPU %"))))
    lines.extend(_summary_records("- Highest Kubernetes restart counts:", pods.get("topRestartedPods"), (("namespace", "namespace"), ("pod", "pod"), ("restartCount", "restarts"), ("phase", "phase"))))
    for snapshot in diagnostics.get("snapshots", [])[:TOP_RECORDS] if isinstance(diagnostics.get("snapshots"), list) else []:
        if not isinstance(snapshot, dict):
            continue
        lines.append(
            f"- Diagnostic snapshot `{snapshot.get('timestamp', 'unavailable')}` kind `{snapshot.get('kind', 'unknown')}`: largest `{snapshot.get('largestProcess', 'unknown')}` current `{_format_bytes(snapshot.get('largestProcessCurrentBytes'))}`; Docker-named processes current `{_format_bytes(snapshot.get('dockerNamedProcessCurrentBytes'))}`; page size `{snapshot.get('pageSizeBytes', 'unavailable')}` bytes"
        )
        lines.extend(_summary_records("  - process evidence (lifetimeMax is per-process and is never summed):", snapshot.get("processes"), (("process", "process"), ("currentBytes", "current bytes"), ("lifetimeMaxBytes", "lifetime max bytes"), ("coalition", "coalition"), ("reason", "reason"))))
        lines.extend(_summary_records("  - Docker-containing same-snapshot coalition totals (all member processes):", snapshot.get("dockerContainingCoalitions"), (("coalition", "coalition"), ("currentBytes", "all-member current bytes"), ("dockerNamedProcessCurrentBytes", "Docker-named current bytes"), ("processCount", "processes"))))
    lines.extend(
        _summary_records(
            "- Incident-to-Docker/Kubernetes churn timing:",
            report.get("incidentCorrelations"),
            (
                ("incidentTime", "incident"),
                ("nearestChurnTime", "nearest churn"),
                ("source", "source"),
                ("absoluteDeltaSeconds", "absolute delta seconds"),
            ),
        )
    )
    for label, section in log_sections:
        value = section if isinstance(section, dict) else {}
        lines.append(
            f"- {label}: matched `{value.get('matchedLineCount', 'unavailable')}`; categories `{json.dumps(value.get('categoryCounts', {}), sort_keys=True)}`; max reported `{_format_bytes(value.get('maxReportedBytes'))}`; latest `{value.get('latestSignalTime', 'unavailable')}`"
        )
        lines.extend(_summary_records("  - implicated components:", value.get("components"), (("component", "component"), ("signalCount", "signals"), ("maxReportedBytes", "max bytes"))))
        lines.extend(_summary_records("  - correlated Docker application-size evidence:", value.get("applicationSizeEvidence"), (("component", "component"), ("kind", "kind"), ("reportedBytes", "reported bytes"), ("samplingTime", "sampled"), ("measurementTime", "measured"), ("deltaSeconds", "delta seconds"))))
    lines.extend(("- Docker stats are container-level: service child processes such as k6 are included in their parent container and do not appear as separate container rows; absence of a row is not evidence that no child ran.", "- Raw logs, IDs, paths, environment values, mounts, arbitrary labels, and device data were discarded.", "- No restart, stop, force-quit, reset, prune, delete, rollout, or runtime health success gate was performed.", ""))
    return "\n".join(lines)


def _write_report(path: Path, report: Mapping[str, Any]) -> None:
    if not path.parent.is_dir():
        raise DiagnosticError("The private runner report directory is unavailable.")
    descriptor, temporary_name = tempfile.mkstemp(prefix=".docker-rca-", suffix=".json", dir=path.parent)
    temporary = Path(temporary_name)
    try:
        os.fchmod(descriptor, 0o600)
        with os.fdopen(descriptor, "w", encoding="utf-8") as stream:
            json.dump(report, stream, sort_keys=True, separators=(",", ":"))
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temporary, path)
    except BaseException:
        try:
            os.close(descriptor)
        except OSError:
            pass
        try:
            temporary.unlink()
        except OSError:
            pass
        raise


def _load_report(path: Path) -> dict[str, Any]:
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, UnicodeError, json.JSONDecodeError):
        return {}
    return value if isinstance(value, dict) else {}


def _ensure_host() -> None:
    if platform.system() != "Darwin" or platform.machine().lower() not in ("arm64", "aarch64"):
        raise DiagnosticError("This diagnostic runs only on the MacBook Air ARM64 runner.")
    if os.environ.get("GITHUB_ACTIONS") != "true":
        raise DiagnosticError("This diagnostic runs only inside GitHub Actions.")
    if os.environ.get("RUNNER_NAME") != EXPECTED_RUNNER_NAME:
        raise DiagnosticError("This diagnostic requires the exact MacBook Air runner identity.")
    for executable in (DOCKER_CLI, KUBECTL):
        if not os.path.isfile(executable) or not os.access(executable, os.X_OK) or os.path.realpath(executable) != executable:
            raise DiagnosticError("An audited diagnostic executable is unavailable.")


def main(argv: Sequence[str] | None = None) -> int:
    effective_argv = list(argv) if argv is not None else sys.argv[1:]
    if effective_argv == ["diagnostic-reports-child"]:
        try:
            result = collect_diagnostic_reports()
            sys.stdout.write(
                json.dumps(
                    {"mode": "sanitized-diagnostic-reports", "result": result},
                    sort_keys=True,
                    separators=(",", ":"),
                )
            )
            return 0
        except BaseException:
            return 1
    parser = argparse.ArgumentParser()
    subparsers = parser.add_subparsers(dest="command", required=True)
    collect_parser = subparsers.add_parser("collect")
    collect_parser.add_argument("--report", required=True, type=Path)
    summary_parser = subparsers.add_parser("render-summary")
    summary_parser.add_argument("--report", required=True, type=Path)
    summary_parser.add_argument("--status", required=True)
    arguments = parser.parse_args(effective_argv)
    if arguments.command == "render-summary":
        sys.stdout.write(render_summary(_load_report(arguments.report), arguments.status))
        return 0
    try:
        _ensure_host()
        _write_report(arguments.report, collect_report())
        print("docker-rca result=snapshot-submitted", flush=True)
        return 0
    except DiagnosticError as error:
        print(f"docker-rca error={error}", file=sys.stderr)
        return 1
    except BaseException:
        print("docker-rca error=unexpected-failure", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
