#!/usr/bin/env python3
"""Host-only diagnostics for a MacBook Air under application-memory pressure.

The helper deliberately does not use the Docker API or inspect Docker Desktop's
protected data. It reads only fixed macOS host counters and the process table.
Raw command output, command lines, environment values, and filesystem paths are
never copied to the report or workflow summary.
"""

from __future__ import annotations

import argparse
import json
import os
import platform
import re
import selectors
import signal
import subprocess
import sys
import tempfile
import time
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Mapping, Sequence


EXPECTED_RUNNER_NAME = "macbook-air-buddystudy"
COMMAND_TIMEOUT_SECONDS = 8
MAX_CAPTURE_BYTES = 1024 * 1024
TOP_PROCESS_GROUP_LIMIT = 8
DOCKER_PROCESS_MARKERS = (
    "docker",
    "com.docker",
    "vpnkit",
    "vfkit",
    "virtiofsd",
)
SAFE_PROCESS_NAME = re.compile(r"[^A-Za-z0-9._+() -]+")
VM_STAT_FIELDS = {
    "Pages free": "freeBytes",
    "Pages active": "activeBytes",
    "Pages inactive": "inactiveBytes",
    "Pages speculative": "speculativeBytes",
    "Pages throttled": "throttledBytes",
    "Pages wired down": "wiredBytes",
    "Pages purgeable": "purgeableBytes",
    "Pages occupied by compressor": "compressorBytes",
    "Pages stored in compressor": "compressedBytes",
}
PRESSURE_NAMES = {1: "normal", 2: "warning", 4: "critical"}


class DiagnosticError(RuntimeError):
    """An allowlisted, secret-free diagnostic failure."""


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


def _terminate_diagnostic_group(process: subprocess.Popen[bytes]) -> None:
    """Terminate only the isolated diagnostic utility group and reap its leader."""

    group_id = process.pid
    for requested_signal, grace_seconds in (
        (signal.SIGTERM, 0.5),
        (signal.SIGKILL, 0.5),
    ):
        if not _process_group_exists(group_id):
            break
        try:
            os.killpg(group_id, requested_signal)
        except ProcessLookupError:
            break
        except OSError:
            pass
        deadline = time.monotonic() + grace_seconds
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
        try:
            process.kill()
        except OSError:
            pass
        try:
            process.wait(timeout=0.5)
        except subprocess.TimeoutExpired:
            pass


def _run_fixed(command: Sequence[str]) -> CommandResult:
    """Run one fixed, read-only utility without exposing its raw failure data."""

    try:
        process = subprocess.Popen(
            tuple(command),
            stdin=subprocess.DEVNULL,
            stdout=subprocess.PIPE,
            stderr=subprocess.DEVNULL,
            start_new_session=True,
            env={
                "PATH": "/usr/bin:/bin:/usr/sbin:/sbin",
                "LANG": "C",
                "LC_ALL": "C",
            },
        )
    except OSError:
        return CommandResult("unavailable")

    assert process.stdout is not None
    selector = selectors.DefaultSelector()
    captured = bytearray()
    deadline = time.monotonic() + COMMAND_TIMEOUT_SECONDS
    reached_eof = False
    try:
        os.set_blocking(process.stdout.fileno(), False)
        selector.register(process.stdout, selectors.EVENT_READ)
        while not reached_eof:
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                _terminate_diagnostic_group(process)
                return CommandResult("timeout")

            events = selector.select(timeout=min(remaining, 0.1))
            for key, _mask in events:
                while True:
                    remaining_capacity = MAX_CAPTURE_BYTES - len(captured)
                    read_size = min(64 * 1024, max(1, remaining_capacity + 1))
                    try:
                        chunk = os.read(key.fd, read_size)
                    except BlockingIOError:
                        break
                    if not chunk:
                        reached_eof = True
                        try:
                            selector.unregister(process.stdout)
                        except (KeyError, ValueError):
                            pass
                        break
                    if len(chunk) > remaining_capacity:
                        _terminate_diagnostic_group(process)
                        return CommandResult("oversize")
                    captured.extend(chunk)

            if process.poll() is not None and not selector.get_map():
                reached_eof = True

        remaining = max(0.0, deadline - time.monotonic())
        try:
            return_code = process.wait(timeout=remaining)
        except subprocess.TimeoutExpired:
            _terminate_diagnostic_group(process)
            return CommandResult("timeout")
        if return_code != 0:
            return CommandResult("command-failed")
        try:
            output = bytes(captured).decode("utf-8", errors="strict")
        except UnicodeDecodeError:
            return CommandResult("invalid")
        return CommandResult("ok", output)
    except (OSError, ValueError):
        _terminate_diagnostic_group(process)
        return CommandResult("unavailable")
    except BaseException:
        _terminate_diagnostic_group(process)
        raise
    finally:
        selector.close()
        process.stdout.close()


def _parse_positive_integer(value: str) -> int | None:
    try:
        parsed = int(value.strip())
    except (TypeError, ValueError):
        return None
    return parsed if parsed >= 0 else None


def parse_vm_stat(output: str) -> dict[str, int] | None:
    page_match = re.search(r"page size of\s+(\d+)\s+bytes", output)
    if page_match is None:
        return None
    page_size = _parse_positive_integer(page_match.group(1))
    if not page_size:
        return None

    result: dict[str, int] = {"pageSizeBytes": page_size}
    for line in output.splitlines():
        if ":" not in line:
            continue
        label, raw_value = line.split(":", 1)
        field = VM_STAT_FIELDS.get(label.strip())
        if field is None:
            continue
        match = re.match(r"\s*([0-9]+)\.?\s*$", raw_value)
        if match is not None:
            result[field] = int(match.group(1)) * page_size
    return result if len(result) > 1 else None


def _unit_bytes(value: str, unit: str) -> int | None:
    try:
        numeric = float(value)
    except ValueError:
        return None
    multiplier = {
        "": 1,
        "K": 1024,
        "M": 1024**2,
        "G": 1024**3,
        "T": 1024**4,
    }.get(unit.upper())
    if numeric < 0 or multiplier is None:
        return None
    return int(numeric * multiplier)


def parse_swap_usage(output: str) -> dict[str, int] | None:
    match = re.search(
        r"total\s*=\s*([0-9.]+)([KMGT]?)\s+"
        r"used\s*=\s*([0-9.]+)([KMGT]?)\s+"
        r"free\s*=\s*([0-9.]+)([KMGT]?)",
        output,
        re.IGNORECASE,
    )
    if match is None:
        return None
    values = {
        "totalBytes": _unit_bytes(match.group(1), match.group(2)),
        "usedBytes": _unit_bytes(match.group(3), match.group(4)),
        "freeBytes": _unit_bytes(match.group(5), match.group(6)),
    }
    if any(value is None for value in values.values()):
        return None
    return {key: int(value) for key, value in values.items()}


def parse_pressure_percentage(output: str) -> int | None:
    match = re.search(
        r"System-wide memory free percentage:\s*([0-9]{1,3})%", output
    )
    if match is None:
        return None
    value = int(match.group(1))
    return value if 0 <= value <= 100 else None


def parse_df(output: str) -> dict[str, int] | None:
    lines = [line for line in output.splitlines() if line.strip()]
    if len(lines) < 2:
        return None
    fields = lines[-1].split()
    if len(fields) < 4:
        return None
    total_kib = _parse_positive_integer(fields[1])
    used_kib = _parse_positive_integer(fields[2])
    available_kib = _parse_positive_integer(fields[3])
    if None in (total_kib, used_kib, available_kib):
        return None
    return {
        "totalBytes": int(total_kib) * 1024,
        "usedBytes": int(used_kib) * 1024,
        "availableBytes": int(available_kib) * 1024,
    }


def _safe_process_basename(raw: str) -> str:
    # `ps comm` is an executable identifier, not a command line. Strip any
    # executable path defensively, then keep a small display-safe alphabet.
    value = raw.strip().rstrip("/").rsplit("/", 1)[-1]
    value = SAFE_PROCESS_NAME.sub("_", value).strip(" ._-")
    return value[:64] or "unknown"


def _is_docker_process(name: str) -> bool:
    folded = name.casefold()
    return any(marker in folded for marker in DOCKER_PROCESS_MARKERS)


def parse_processes(output: str) -> dict[str, Any] | None:
    groups: dict[str, dict[str, Any]] = {}
    sample_count = 0
    summed_rss = 0
    for line in output.splitlines():
        fields = line.strip().split(None, 2)
        if len(fields) != 3:
            continue
        rss_kib = _parse_positive_integer(fields[0])
        virtual_kib = _parse_positive_integer(fields[1])
        if rss_kib is None or virtual_kib is None:
            continue
        name = _safe_process_basename(fields[2])
        group = groups.setdefault(
            name,
            {
                "name": name,
                "count": 0,
                "rssBytes": 0,
                "virtualBytes": 0,
                "dockerRelated": _is_docker_process(name),
            },
        )
        group["count"] += 1
        group["rssBytes"] += rss_kib * 1024
        group["virtualBytes"] += virtual_kib * 1024
        sample_count += 1
        summed_rss += rss_kib * 1024

    if not groups:
        return None
    ordered = sorted(
        groups.values(), key=lambda item: (-item["rssBytes"], item["name"])
    )
    docker_groups = [item for item in ordered if item["dockerRelated"]]
    return {
        "sampleCount": sample_count,
        "summedRssBytes": summed_rss,
        "dockerProcessCount": sum(item["count"] for item in docker_groups),
        "dockerRssBytes": sum(item["rssBytes"] for item in docker_groups),
        "dockerVirtualBytes": sum(item["virtualBytes"] for item in docker_groups),
        "topRssByExecutable": ordered[:TOP_PROCESS_GROUP_LIMIT],
        "dockerExecutables": docker_groups[:TOP_PROCESS_GROUP_LIMIT],
    }


def _record_probe(
    report: dict[str, Any],
    name: str,
    command: Sequence[str],
    parser,
    destination: str,
) -> None:
    print(f"pressure-diagnostic stage={name} status=start", flush=True)
    result = _run_fixed(command)
    value = parser(result.output) if result.status == "ok" else None
    status = "ok" if result.status == "ok" and value is not None else result.status
    if result.status == "ok" and value is None:
        status = "invalid"
    report["probes"][name] = status
    if status == "ok":
        report[destination] = value
    print(f"pressure-diagnostic stage={name} status={status}", flush=True)


def collect_report() -> dict[str, Any]:
    report: dict[str, Any] = {
        "mode": "pressure-diagnostics",
        "schemaVersion": 1,
        "readOnly": True,
        "probes": {},
    }

    _record_probe(
        report,
        "physical-memory",
        ("/usr/sbin/sysctl", "-n", "hw.memsize"),
        _parse_positive_integer,
        "physicalMemoryBytes",
    )
    _record_probe(
        report,
        "virtual-memory",
        ("/usr/bin/vm_stat",),
        parse_vm_stat,
        "virtualMemory",
    )
    _record_probe(
        report,
        "swap",
        ("/usr/sbin/sysctl", "-n", "vm.swapusage"),
        parse_swap_usage,
        "swap",
    )
    _record_probe(
        report,
        "pressure-level",
        ("/usr/sbin/sysctl", "-n", "kern.memorystatus_vm_pressure_level"),
        _parse_positive_integer,
        "pressureLevel",
    )
    _record_probe(
        report,
        "pressure-percentage",
        ("/usr/bin/memory_pressure", "-Q"),
        parse_pressure_percentage,
        "memoryFreePercentage",
    )
    _record_probe(
        report,
        "data-volume-disk",
        ("/bin/df", "-Pk", "/System/Volumes/Data"),
        parse_df,
        "dataVolumeDisk",
    )
    _record_probe(
        report,
        "process-rss",
        ("/bin/ps", "-axww", "-o", "rss=", "-o", "vsz=", "-o", "comm="),
        parse_processes,
        "processes",
    )

    level = report.get("pressureLevel")
    report["pressureName"] = PRESSURE_NAMES.get(level, "unknown")
    return report


def _write_report(path: Path, report: Mapping[str, Any]) -> None:
    if not path.parent.is_dir():
        raise DiagnosticError("The private runner report directory is unavailable.")
    descriptor, temporary_name = tempfile.mkstemp(
        prefix=".macbookair-pressure-", suffix=".json", dir=path.parent
    )
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


def _format_bytes(value: Any) -> str:
    if not isinstance(value, int) or isinstance(value, bool) or value < 0:
        return "unavailable"
    gib = value / (1024**3)
    if gib >= 1:
        return f"{gib:.2f} GiB"
    return f"{value / (1024**2):.1f} MiB"


def _render_groups(groups: Any) -> list[str]:
    if not isinstance(groups, list) or not groups:
        return ["  - unavailable"]
    lines = []
    for group in groups[:TOP_PROCESS_GROUP_LIMIT]:
        if not isinstance(group, dict):
            continue
        name = _safe_process_basename(str(group.get("name", "unknown")))
        count = group.get("count", 0)
        rss = _format_bytes(group.get("rssBytes"))
        lines.append(f"  - `{name}`: {rss} RSS across {count} process(es)")
    return lines or ["  - unavailable"]


def render_summary(report: Mapping[str, Any], workflow_status: str) -> str:
    if report.get("mode") != "pressure-diagnostics":
        return "\n".join(
            (
                "## MacBook Air host pressure diagnostics",
                "",
                f"- Workflow status: `{workflow_status}`",
                "- Snapshot result: `not submitted`",
                "- Report: unavailable; inspect only the fixed probe status lines.",
                "- No Docker API call or runtime mutation was attempted.",
                "",
            )
        )

    probes = report.get("probes") if isinstance(report.get("probes"), dict) else {}
    successful = sum(value == "ok" for value in probes.values())
    total = len(probes)
    swap = report.get("swap") if isinstance(report.get("swap"), dict) else {}
    disk = (
        report.get("dataVolumeDisk")
        if isinstance(report.get("dataVolumeDisk"), dict)
        else {}
    )
    processes = (
        report.get("processes") if isinstance(report.get("processes"), dict) else {}
    )
    virtual_memory = (
        report.get("virtualMemory")
        if isinstance(report.get("virtualMemory"), dict)
        else {}
    )
    pressure_level = report.get("pressureLevel", "unavailable")
    pressure_name = str(report.get("pressureName", "unknown"))

    lines = [
        "## MacBook Air host pressure diagnostics",
        "",
        f"- Workflow status: `{workflow_status}`",
        f"- Snapshot result: `{'submitted' if workflow_status == 'success' else 'not submitted'}`",
        f"- Read-only probes: `{successful}/{total}` completed",
        f"- Physical memory: `{_format_bytes(report.get('physicalMemoryBytes'))}`",
        f"- Memory pressure: `{pressure_name}` (level `{pressure_level}`); free estimate `{report.get('memoryFreePercentage', 'unavailable')}%`",
        f"- VM pages: free `{_format_bytes(virtual_memory.get('freeBytes'))}`, inactive `{_format_bytes(virtual_memory.get('inactiveBytes'))}`, wired `{_format_bytes(virtual_memory.get('wiredBytes'))}`, compressor `{_format_bytes(virtual_memory.get('compressorBytes'))}`",
        f"- Swap: `{_format_bytes(swap.get('usedBytes'))}` used / `{_format_bytes(swap.get('totalBytes'))}` total",
        f"- Data volume: `{_format_bytes(disk.get('usedBytes'))}` used / `{_format_bytes(disk.get('totalBytes'))}` total; `{_format_bytes(disk.get('availableBytes'))}` available",
        f"- Sampled process RSS sum: `{_format_bytes(processes.get('summedRssBytes'))}` across `{processes.get('sampleCount', 'unavailable')}` processes",
        f"- Docker-related host RSS: `{_format_bytes(processes.get('dockerRssBytes'))}` across `{processes.get('dockerProcessCount', 'unavailable')}` processes",
        f"- Docker-related virtual address space: `{_format_bytes(processes.get('dockerVirtualBytes'))}` (not resident RAM)",
        "- Docker-related executable groups:",
        *_render_groups(processes.get("dockerExecutables")),
        "- Largest executable groups by RSS:",
        *_render_groups(processes.get("topRssByExecutable")),
        "- This run did not call Docker, inspect protected Docker data, restart/stop a runtime, perform a health check, or remove data.",
        "",
    ]
    return "\n".join(lines)


def _ensure_host() -> None:
    if platform.system() != "Darwin" or platform.machine().lower() not in (
        "arm64",
        "aarch64",
    ):
        raise DiagnosticError("This diagnostic runs only on the MacBook Air ARM64 runner.")
    if os.environ.get("GITHUB_ACTIONS") != "true":
        raise DiagnosticError("This diagnostic runs only inside the audited GitHub Actions job.")
    if os.environ.get("RUNNER_NAME") != EXPECTED_RUNNER_NAME:
        raise DiagnosticError("This diagnostic requires the exact MacBook Air runner identity.")


def main(argv: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser()
    subparsers = parser.add_subparsers(dest="command", required=True)
    collect_parser = subparsers.add_parser("collect")
    collect_parser.add_argument("--report", required=True, type=Path)
    summary_parser = subparsers.add_parser("render-summary")
    summary_parser.add_argument("--report", required=True, type=Path)
    summary_parser.add_argument("--status", required=True)
    arguments = parser.parse_args(argv)

    if arguments.command == "render-summary":
        sys.stdout.write(render_summary(_load_report(arguments.report), arguments.status))
        return 0

    try:
        _ensure_host()
        report = collect_report()
        _write_report(arguments.report, report)
        print("pressure-diagnostic result=snapshot-submitted", flush=True)
        return 0
    except DiagnosticError as error:
        print(f"pressure-diagnostic error={error}", file=sys.stderr)
        return 1
    except BaseException:
        print("pressure-diagnostic error=unexpected-failure", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
