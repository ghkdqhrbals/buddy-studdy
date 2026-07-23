#!/usr/bin/env python3
import argparse
import json
import os
import platform
import signal
import subprocess
import time
from datetime import datetime, timezone
from pathlib import Path


STOP = False


def stop(_signum, _frame):
    global STOP
    STOP = True


def run(command, timeout=2.0):
    try:
        return subprocess.run(
            command,
            check=True,
            capture_output=True,
            text=True,
            timeout=timeout,
        ).stdout.strip()
    except (OSError, subprocess.SubprocessError):
        return ""


def process_sample(pid):
    if not pid:
        return {}
    output = run(["ps", "-o", "pcpu=,rss=,vsz=", "-p", str(pid)])
    fields = output.split()
    if len(fields) < 3:
        return {}
    return {
        "cpuPercent": float(fields[0]),
        "rssBytes": int(fields[1]) * 1024,
        "vszBytes": int(fields[2]) * 1024,
    }


def mac_memory():
    output = run(["vm_stat"])
    if not output:
        return {}
    page_size = 4096
    values = {}
    for line in output.splitlines():
        if "page size of" in line:
            try:
                page_size = int(line.split("page size of", 1)[1].split("bytes", 1)[0].strip())
            except ValueError:
                pass
            continue
        if ":" not in line:
            continue
        key, raw = line.split(":", 1)
        try:
            values[key.strip()] = int(raw.strip().rstrip("."))
        except ValueError:
            continue
    free_pages = values.get("Pages free", 0) + values.get("Pages speculative", 0)
    inactive_pages = values.get("Pages inactive", 0)
    total_raw = run(["sysctl", "-n", "hw.memsize"])
    total = int(total_raw) if total_raw.isdigit() else 0
    available = (free_pages + inactive_pages) * page_size
    return {
        "totalBytes": total,
        "availableBytes": available,
        "usedBytes": max(0, total - available) if total else 0,
    }


def linux_memory():
    values = {}
    try:
        for line in Path("/proc/meminfo").read_text().splitlines():
            key, raw = line.split(":", 1)
            values[key] = int(raw.strip().split()[0]) * 1024
    except (OSError, ValueError):
        return {}
    total = values.get("MemTotal", 0)
    available = values.get("MemAvailable", 0)
    return {
        "totalBytes": total,
        "availableBytes": available,
        "usedBytes": max(0, total - available),
    }


def host_cpu():
    output = run(["ps", "-A", "-o", "pcpu="])
    values = []
    for raw in output.splitlines():
        try:
            values.append(float(raw.strip()))
        except ValueError:
            continue
    cpu_count = os.cpu_count() or 1
    return {
        "aggregatePercent": sum(values),
        "normalizedPercent": min(100.0, sum(values) / cpu_count),
        "logicalCpuCount": cpu_count,
    }


def network_totals():
    system = platform.system()
    command = ["netstat", "-ibn"] if system == "Darwin" else ["cat", "/proc/net/dev"]
    output = run(command)
    received = 0
    sent = 0
    receive_errors = 0
    transmit_errors = 0
    receive_drops = 0
    transmit_drops = 0
    if system == "Darwin":
        seen = set()
        for line in output.splitlines():
            fields = line.split()
            if len(fields) < 10 or fields[0] in {"Name", "lo0"}:
                continue
            key = (fields[0], fields[3])
            if key in seen:
                continue
            seen.add(key)
            try:
                received += int(fields[6])
                sent += int(fields[9])
                receive_errors += int(fields[5])
                transmit_errors += int(fields[8])
                if len(fields) > 11:
                    transmit_drops += int(fields[11])
            except (ValueError, IndexError):
                continue
    else:
        for line in output.splitlines():
            if ":" not in line:
                continue
            name, raw = line.split(":", 1)
            if name.strip() == "lo":
                continue
            fields = raw.split()
            try:
                received += int(fields[0])
                sent += int(fields[8])
                receive_errors += int(fields[2])
                receive_drops += int(fields[3])
                transmit_errors += int(fields[10])
                transmit_drops += int(fields[11])
            except (ValueError, IndexError):
                continue
    return {
        "receivedBytes": received,
        "sentBytes": sent,
        "receiveErrors": receive_errors,
        "transmitErrors": transmit_errors,
        "receiveDrops": receive_drops,
        "transmitDrops": transmit_drops,
    }


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--pid", type=int)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--interval", type=float, default=1.0)
    parser.add_argument("--stop-file", type=Path)
    args = parser.parse_args()

    signal.signal(signal.SIGTERM, stop)
    signal.signal(signal.SIGINT, stop)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    with args.output.open("w", buffering=1) as handle:
        while not STOP:
            if args.stop_file and args.stop_file.exists():
                break
            started = time.monotonic()
            handle.write(
                json.dumps(
                    {
                        "timestamp": datetime.now(timezone.utc).isoformat(),
                        "process": process_sample(args.pid),
                        "hostCpu": host_cpu(),
                        "hostMemory": mac_memory() if platform.system() == "Darwin" else linux_memory(),
                        "network": network_totals(),
                    },
                    separators=(",", ":"),
                )
                + "\n"
            )
            delay = args.interval - (time.monotonic() - started)
            if delay > 0:
                time.sleep(delay)


if __name__ == "__main__":
    main()
