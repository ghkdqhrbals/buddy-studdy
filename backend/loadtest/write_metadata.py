#!/usr/bin/env python3
import argparse
import json
import os
import platform
import shutil
import subprocess
from datetime import datetime, timezone
from pathlib import Path


def version(command):
    try:
        output = subprocess.run(
            command,
            check=True,
            capture_output=True,
            text=True,
            timeout=10,
        )
        return (output.stdout or output.stderr).splitlines()[0].strip()
    except (OSError, subprocess.SubprocessError, IndexError):
        return "unavailable"


def physical_memory_bytes():
    if platform.system() == "Darwin":
        try:
            return int(
                subprocess.run(
                    ["sysctl", "-n", "hw.memsize"],
                    check=True,
                    capture_output=True,
                    text=True,
                    timeout=5,
                ).stdout.strip()
            )
        except (OSError, subprocess.SubprocessError, ValueError):
            return None
    try:
        for line in Path("/proc/meminfo").read_text().splitlines():
            if line.startswith("MemTotal:"):
                return int(line.split()[1]) * 1024
    except (OSError, ValueError, IndexError):
        pass
    return None


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--profile", required=True)
    parser.add_argument("--tool", required=True)
    parser.add_argument("--mvc-ref", required=True)
    parser.add_argument("--webflux-ref", required=True)
    parser.add_argument("--target-host", required=True)
    parser.add_argument("--load-generator", required=True)
    parser.add_argument("--rounds", type=int, required=True)
    parser.add_argument("--target-rps", required=True)
    parser.add_argument("--vusers", required=True)
    parser.add_argument("--max-concurrent-users", type=int, required=True)
    parser.add_argument("--ngrinder-max-processes", type=int, required=True)
    parser.add_argument("--ngrinder-max-threads-per-process", type=int, required=True)
    parser.add_argument("--scenarios", required=True)
    parser.add_argument("--duration", required=True)
    parser.add_argument("--heap", required=True)
    parser.add_argument("--cpu", required=True)
    parser.add_argument("--db-pool", required=True)
    parser.add_argument("--jfr", required=True)
    parser.add_argument("--nmt", required=True)
    parser.add_argument("--generator-machine-file", type=Path, required=True)
    parser.add_argument("--java-bin", required=True)
    parser.add_argument("--generator-network-capacity-mbps", type=float, default=0)
    args = parser.parse_args()
    generator_machine = json.loads(args.generator_machine_file.read_text())

    data = {
        "schemaVersion": 1,
        "createdAt": datetime.now(timezone.utc).isoformat(),
        "profile": args.profile,
        "tool": args.tool,
        "refs": {"mvc": args.mvc_ref, "webflux": args.webflux_ref},
        "execution": {
            "targetHost": args.target_host,
            "loadGenerator": args.load_generator,
            "rounds": args.rounds,
            "targetRps": [int(value) for value in args.target_rps.split(",")],
            "vusers": [int(value) for value in args.vusers.split(",")],
            "maxConcurrentUsers": args.max_concurrent_users,
            "ngrinderExecution": {
                "agentCount": 1,
                "maxProcesses": args.ngrinder_max_processes,
                "maxThreadsPerProcess": args.ngrinder_max_threads_per_process,
            },
            "scenarios": [value for value in args.scenarios.split(",") if value],
            "duration": args.duration,
        },
        "limits": {
            "jvmHeap": args.heap,
            "visibleCpu": int(args.cpu),
            "databasePool": int(args.db_pool),
            "loadGeneratorNetworkCapacityMbps": (
                args.generator_network_capacity_mbps
                if args.generator_network_capacity_mbps > 0
                else None
            ),
        },
        "diagnostics": {"jfr": args.jfr == "true", "nmt": args.nmt == "true"},
        "targetMachine": {
            "hostname": platform.node(),
            "system": platform.platform(),
            "logicalCpuCount": os.cpu_count(),
            "architecture": platform.machine(),
            "physicalMemoryBytes": physical_memory_bytes(),
            "diskFreeBytes": shutil.disk_usage(Path.cwd()).free,
        },
        "loadGeneratorMachine": generator_machine,
        "versions": {
            "java": version([args.java_bin, "-version"]),
            "docker": version(["docker", "--version"]),
            "k6": version(["k6", "version"]) if shutil.which("k6") else "remote or unavailable",
            "ngrinder": "3.5.9-p1",
            "python": platform.python_version(),
        },
        "fixtureVersion": "seed.sql:v1:1-user-100-studies-500-public-questions",
    }
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(data, indent=2) + "\n")


if __name__ == "__main__":
    main()
