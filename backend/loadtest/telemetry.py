#!/usr/bin/env python3
import argparse
import json
import signal
import subprocess
import time
import urllib.parse
import urllib.request
from datetime import datetime, timezone
from pathlib import Path


METRICS = (
    ("process.cpu", "process.cpu.usage", ()),
    ("system.cpu", "system.cpu.usage", ()),
    ("system.load_1m", "system.load.average.1m", ()),
    ("process.open_files", "process.files.open", ()),
    ("process.max_files", "process.files.max", ()),
    ("jvm.heap.used", "jvm.memory.used", (("area", "heap"),)),
    ("jvm.heap.committed", "jvm.memory.committed", (("area", "heap"),)),
    ("jvm.heap.max", "jvm.memory.max", (("area", "heap"),)),
    ("jvm.nonheap.used", "jvm.memory.used", (("area", "nonheap"),)),
    ("jvm.direct.used", "jvm.buffer.memory.used", (("id", "direct"),)),
    ("jvm.direct.count", "jvm.buffer.count", (("id", "direct"),)),
    ("jvm.threads.live", "jvm.threads.live", ()),
    ("jvm.threads.peak", "jvm.threads.peak", ()),
    ("jvm.threads.daemon", "jvm.threads.daemon", ()),
    ("jvm.gc.pause", "jvm.gc.pause", ()),
    ("jvm.gc.allocated", "jvm.gc.memory.allocated", ()),
    ("jvm.gc.promoted", "jvm.gc.memory.promoted", ()),
    ("hikari.active", "hikaricp.connections.active", ()),
    ("hikari.idle", "hikaricp.connections.idle", ()),
    ("hikari.pending", "hikaricp.connections.pending", ()),
    ("hikari.max", "hikaricp.connections.max", ()),
    ("hikari.min", "hikaricp.connections.min", ()),
    ("r2dbc.acquired", "r2dbc.pool.acquired", ()),
    ("r2dbc.allocated", "r2dbc.pool.allocated", ()),
    ("r2dbc.idle", "r2dbc.pool.idle", ()),
    ("r2dbc.pending", "r2dbc.pool.pending", ()),
    ("r2dbc.max_allocated", "r2dbc.pool.max.allocated", ()),
    ("r2dbc.max_pending", "r2dbc.pool.max.pending", ()),
    ("tomcat.busy", "tomcat.threads.busy", ()),
    ("tomcat.current", "tomcat.threads.current", ()),
    ("tomcat.max", "tomcat.threads.config.max", ()),
    ("webflux.executor.active", "executor.active", (("name", "webFluxBlockingExecutor"),)),
    ("webflux.executor.pool", "executor.pool.size", (("name", "webFluxBlockingExecutor"),)),
    ("webflux.executor.core", "executor.pool.core", (("name", "webFluxBlockingExecutor"),)),
    ("webflux.executor.max", "executor.pool.max", (("name", "webFluxBlockingExecutor"),)),
    ("webflux.executor.queued", "executor.queued", (("name", "webFluxBlockingExecutor"),)),
    ("webflux.executor.queue_remaining", "executor.queue.remaining", (("name", "webFluxBlockingExecutor"),)),
    ("webflux.executor.completed", "executor.completed", (("name", "webFluxBlockingExecutor"),)),
    ("http.active", "http.server.requests.active", ()),
    ("reactor.pending_tasks", "reactor.netty.eventloop.pending.tasks", ()),
    ("reactor.connections.active", "reactor.netty.connection.provider.active.connections", ()),
    ("reactor.connections.idle", "reactor.netty.connection.provider.idle.connections", ()),
    ("reactor.connections.pending", "reactor.netty.connection.provider.pending.connections", ()),
)

STOP = False


def stop(_signum, _frame):
    global STOP
    STOP = True


def run(command, timeout=1.5):
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


def fetch_json(url, timeout=1.0):
    try:
        with urllib.request.urlopen(url, timeout=timeout) as response:
            return json.load(response)
    except (OSError, ValueError):
        return None


def process_sample(pid):
    output = run(["ps", "-o", "pcpu=,rss=,vsz=", "-p", str(pid)])
    if not output:
        return {}
    fields = output.split()
    if len(fields) < 3:
        return {}
    thread_rows = run(["ps", "-M", "-p", str(pid)])
    return {
        "cpu_percent": float(fields[0]),
        "rss_bytes": int(fields[1]) * 1024,
        "vsz_bytes": int(fields[2]) * 1024,
        "os_threads": max(0, len(thread_rows.splitlines()) - 1) if thread_rows else None,
    }


def actuator_metric(base_url, name, tags):
    query = urllib.parse.urlencode([("tag", f"{key}:{value}") for key, value in tags])
    url = f"{base_url}/actuator/metrics/{name}"
    if query:
        url = f"{url}?{query}"
    data = fetch_json(url)
    if not data:
        return {}
    result = {}
    for measurement in data.get("measurements", []):
        statistic = str(measurement.get("statistic", "value")).lower()
        value = measurement.get("value")
        if isinstance(value, (int, float)):
            result[statistic] = float(value)
    return result


def actuator_sample(base_url, available):
    values = {}
    for alias, name, tags in METRICS:
        if STOP:
            break
        if name not in available:
            continue
        measurements = actuator_metric(base_url, name, tags)
        for statistic, value in measurements.items():
            suffix = "" if statistic == "value" else f".{statistic}"
            values[f"{alias}{suffix}"] = value
    return values


def mysql_sample(container):
    sql = """
select json_object(
  'connections_total', max(if(variable_name = 'Threads_connected', variable_value, null)),
  'connections_active', max(if(variable_name = 'Threads_running', variable_value, null)),
  'connections_waiting', (
    select count(*) from performance_schema.threads
    where type = 'FOREGROUND' and processlist_state like 'Waiting%'
  ),
  'commits', max(if(variable_name = 'Com_commit', variable_value, null)),
  'rollbacks', max(if(variable_name = 'Com_rollback', variable_value, null)),
  'buffer_pool_reads', max(if(variable_name = 'Innodb_buffer_pool_reads', variable_value, null)),
  'buffer_pool_read_requests', max(if(variable_name = 'Innodb_buffer_pool_read_requests', variable_value, null)),
  'created_tmp_disk_tables', max(if(variable_name = 'Created_tmp_disk_tables', variable_value, null)),
  'deadlocks', max(if(variable_name = 'Innodb_deadlocks', variable_value, null)),
  'rows_read', max(if(variable_name = 'Innodb_rows_read', variable_value, null)),
  'rows_inserted', max(if(variable_name = 'Innodb_rows_inserted', variable_value, null)),
  'rows_updated', max(if(variable_name = 'Innodb_rows_updated', variable_value, null)),
  'rows_deleted', max(if(variable_name = 'Innodb_rows_deleted', variable_value, null))
)
from performance_schema.global_status;
"""
    output = run(
        [
            "docker", "exec", "-e", "MYSQL_PWD=benchmark-password", container,
            "mysql", "-N", "-B", "-u", "buddystudy", "buddystudy", "-e", sql,
        ],
        timeout=2.0,
    )
    try:
        return json.loads(output) if output else {}
    except ValueError:
        return {}


def redis_sample(container):
    output = run(["docker", "exec", container, "redis-cli", "--raw", "INFO"], timeout=2.0)
    wanted = {
        "used_memory",
        "used_memory_rss",
        "connected_clients",
        "blocked_clients",
        "instantaneous_ops_per_sec",
        "total_commands_processed",
        "keyspace_hits",
        "keyspace_misses",
        "evicted_keys",
        "rejected_connections",
    }
    values = {}
    for line in output.splitlines():
        if ":" not in line or line.startswith("#"):
            continue
        key, raw_value = line.rstrip("\r").split(":", 1)
        if key not in wanted:
            continue
        try:
            values[key] = int(raw_value)
        except ValueError:
            continue
    return values


def parse_size(raw):
    units = {
        "B": 1,
        "kB": 1000,
        "KB": 1000,
        "KiB": 1024,
        "MB": 1000**2,
        "MiB": 1024**2,
        "GB": 1000**3,
        "GiB": 1024**3,
    }
    raw = raw.strip()
    for unit in sorted(units, key=len, reverse=True):
        if raw.endswith(unit):
            try:
                return float(raw[: -len(unit)].strip()) * units[unit]
            except ValueError:
                return None
    return None


def container_samples(containers):
    output = run(
        ["docker", "stats", "--no-stream", "--format", "{{json .}}", *containers],
        timeout=3.0,
    )
    result = {}
    for line in output.splitlines():
        try:
            row = json.loads(line)
        except ValueError:
            continue
        name = row.get("Name")
        if not name:
            continue
        memory_used = str(row.get("MemUsage", "")).split("/")[0].strip()
        try:
            cpu_percent = float(str(row.get("CPUPerc", "0")).rstrip("%"))
        except ValueError:
            cpu_percent = None
        try:
            pids = int(row.get("PIDs", 0))
        except (TypeError, ValueError):
            pids = None
        result[name] = {
            "cpu_percent": cpu_percent,
            "memory_bytes": parse_size(memory_used),
            "pids": pids,
        }
    return result


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--pid", type=int, required=True)
    parser.add_argument("--load-generator-pid", type=int)
    parser.add_argument("--base-url", required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--interval", type=float, default=2.0)
    parser.add_argument("--mysql-container", required=True)
    parser.add_argument("--redis-container", required=True)
    args = parser.parse_args()

    signal.signal(signal.SIGTERM, stop)
    signal.signal(signal.SIGINT, stop)
    args.output.parent.mkdir(parents=True, exist_ok=True)

    names = fetch_json(f"{args.base_url}/actuator/metrics") or {}
    available = set(names.get("names", []))
    with args.output.open("w", buffering=1) as handle:
        while not STOP:
            started = time.monotonic()
            actuator = actuator_sample(args.base_url, available)
            if STOP:
                break
            sample = {
                "timestamp": datetime.now(timezone.utc).isoformat(),
                "process": process_sample(args.pid),
                "load_generator": process_sample(args.load_generator_pid) if args.load_generator_pid else {},
                "actuator": actuator,
                "mysql": mysql_sample(args.mysql_container),
                "redis": redis_sample(args.redis_container),
                "containers": container_samples([args.mysql_container, args.redis_container]),
            }
            handle.write(json.dumps(sample, separators=(",", ":")) + "\n")
            delay = args.interval - (time.monotonic() - started)
            if delay > 0:
                time.sleep(delay)


if __name__ == "__main__":
    main()
