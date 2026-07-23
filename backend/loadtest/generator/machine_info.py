#!/usr/bin/env python3
import json
import os
import platform
import shutil
import subprocess
import time
from pathlib import Path


def command_version(command: list[str]) -> str:
    try:
        completed = subprocess.run(
            command,
            check=True,
            capture_output=True,
            text=True,
            timeout=10,
        )
        return (completed.stdout or completed.stderr).splitlines()[0].strip()
    except (OSError, subprocess.SubprocessError, IndexError):
        return "unavailable"


def memory_bytes() -> int | None:
    if Path("/proc/meminfo").exists():
        for line in Path("/proc/meminfo").read_text().splitlines():
            if line.startswith("MemTotal:"):
                return int(line.split()[1]) * 1024
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


def main() -> None:
    disk = shutil.disk_usage(Path.cwd())
    data = {
        "capturedAtEpochNs": time.time_ns(),
        "hostname": platform.node(),
        "system": platform.platform(),
        "architecture": platform.machine(),
        "logicalCpuCount": os.cpu_count(),
        "memoryBytes": memory_bytes(),
        "disk": {
            "path": str(Path.cwd()),
            "totalBytes": disk.total,
            "freeBytes": disk.free,
        },
        "versions": {
            "python": platform.python_version(),
            "k6": command_version(["k6", "version"]),
            "docker": command_version(["docker", "--version"]),
            "dockerCompose": command_version(["docker", "compose", "version"]),
        },
    }
    print(json.dumps(data, separators=(",", ":")))


if __name__ == "__main__":
    main()
