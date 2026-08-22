#!/usr/bin/env python3
"""Recreate the two legacy TestZone data containers with bounded logging.

The Docker inspect documents contain credentials, so they stay in memory and
are never included in command output, exception text, or the migration report.
"""

from __future__ import annotations

import argparse
import copy
import http.client
import json
import os
import re
import socket
import subprocess
import sys
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Mapping, Sequence
from urllib.parse import quote, unquote, urlparse


BOUNDED_LOG_CONFIG = {
    "Type": "local",
    "Config": {
        "max-size": "10m",
        "max-file": "3",
        "compress": "true",
    },
}


@dataclass(frozen=True)
class TargetSpec:
    name: str
    images: frozenset[str]
    volume_destination: str


TARGETS = (
    TargetSpec(
        name="buddystudy-testzone-postgres",
        images=frozenset(("postgres:16-alpine", "postgres:17-alpine")),
        volume_destination="/var/lib/postgresql/data",
    ),
    TargetSpec(
        name="buddystudy-testzone-redis",
        images=frozenset(("redis:7.4-alpine", "redis:8-alpine")),
        volume_destination="/data",
    ),
)

RESOURCE_KEYS = (
    "CpuShares",
    "Memory",
    "NanoCpus",
    "CgroupParent",
    "BlkioWeight",
    "BlkioWeightDevice",
    "BlkioDeviceReadBps",
    "BlkioDeviceWriteBps",
    "BlkioDeviceReadIOps",
    "BlkioDeviceWriteIOps",
    "CpuPeriod",
    "CpuQuota",
    "CpuRealtimePeriod",
    "CpuRealtimeRuntime",
    "CpusetCpus",
    "CpusetMems",
    "Devices",
    "DeviceCgroupRules",
    "DeviceRequests",
    "KernelMemoryTCP",
    "MemoryReservation",
    "MemorySwap",
    "MemorySwappiness",
    "OomKillDisable",
    "PidsLimit",
    "Ulimits",
    "CpuCount",
    "CpuPercent",
    "IOMaximumIOps",
    "IOMaximumBandwidth",
)


class MigrationError(RuntimeError):
    """A safe, secret-free migration failure."""


class UnixHTTPConnection(http.client.HTTPConnection):
    def __init__(self, socket_path: str, timeout: float = 60) -> None:
        super().__init__("localhost", timeout=timeout)
        self.socket_path = socket_path

    def connect(self) -> None:
        connection = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
        connection.settimeout(self.timeout)
        connection.connect(self.socket_path)
        self.sock = connection


class DockerRuntime:
    """Small Docker adapter that never writes inspect/config JSON to stdout."""

    def __init__(self) -> None:
        self._socket_path: str | None = None
        self._api_version: str | None = None

    @staticmethod
    def _run(
        arguments: Sequence[str],
        *,
        timeout: int = 60,
        check: bool = True,
    ) -> subprocess.CompletedProcess[str]:
        try:
            return subprocess.run(
                list(arguments),
                check=check,
                capture_output=True,
                text=True,
                timeout=timeout,
            )
        except (subprocess.SubprocessError, OSError) as error:
            raise MigrationError("Docker command failed; no container configuration was printed.") from error

    def ensure_ready(self) -> None:
        self._run(("docker", "info"), timeout=15)

    def inspect(self, name: str, *, allow_missing: bool = False) -> dict[str, Any] | None:
        result = self._run(
            ("docker", "container", "inspect", name),
            timeout=30,
            check=False,
        )
        if result.returncode != 0:
            if allow_missing:
                return None
            raise MigrationError(f"Required TestZone container is unavailable: {name}")
        try:
            documents = json.loads(result.stdout)
        except (TypeError, json.JSONDecodeError) as error:
            raise MigrationError(f"Docker returned invalid inspect data for {name}.") from error
        if not isinstance(documents, list) or len(documents) != 1:
            raise MigrationError(f"Docker returned ambiguous inspect data for {name}.")
        document = documents[0]
        if not isinstance(document, dict):
            raise MigrationError(f"Docker returned invalid inspect data for {name}.")
        return document

    def list_names(self, prefix: str) -> list[str]:
        result = self._run(
            (
                "docker",
                "ps",
                "--all",
                "--filter",
                f"name=^/{prefix}",
                "--format",
                "{{.Names}}",
            ),
            timeout=30,
        )
        return [
            name
            for name in result.stdout.splitlines()
            if name.startswith(prefix)
        ]

    def stop(self, name: str) -> None:
        self._run(("docker", "stop", "--time", "60", name), timeout=75)

    def rename(self, current_name: str, next_name: str) -> None:
        self._run(("docker", "rename", current_name, next_name), timeout=30)

    def start(self, name: str) -> None:
        self._run(("docker", "start", name), timeout=60)

    def remove(self, name: str, *, force: bool = False) -> None:
        arguments = ["docker", "rm"]
        if force:
            arguments.append("--force")
        arguments.append(name)
        self._run(arguments, timeout=60)

    def _discover_engine(self) -> tuple[str, str]:
        if self._socket_path and self._api_version:
            return self._socket_path, self._api_version

        docker_host = os.environ.get("DOCKER_HOST", "").strip()
        if not docker_host:
            result = self._run(
                (
                    "docker",
                    "context",
                    "inspect",
                    "--format",
                    "{{.Endpoints.docker.Host}}",
                ),
                timeout=15,
            )
            docker_host = result.stdout.strip()
        parsed = urlparse(docker_host)
        if parsed.scheme != "unix" or not parsed.path:
            raise MigrationError("The audited migration requires a local Docker unix socket.")

        version_result = self._run(
            ("docker", "version", "--format", "{{.Server.APIVersion}}"),
            timeout=15,
        )
        api_version = version_result.stdout.strip()
        if not api_version or not all(part.isdigit() for part in api_version.split(".")):
            raise MigrationError("Docker did not provide a valid Engine API version.")

        self._socket_path = unquote(parsed.path)
        self._api_version = api_version
        return self._socket_path, self._api_version

    def create(self, name: str, payload: Mapping[str, Any]) -> None:
        socket_path, api_version = self._discover_engine()
        connection = UnixHTTPConnection(socket_path)
        body = json.dumps(payload, separators=(",", ":")).encode("utf-8")
        try:
            connection.request(
                "POST",
                f"/v{api_version}/containers/create?name={quote(name, safe='')}",
                body=body,
                headers={
                    "Content-Type": "application/json",
                    "Content-Length": str(len(body)),
                },
            )
            response = connection.getresponse()
            response.read()
        except (OSError, http.client.HTTPException) as error:
            raise MigrationError(f"Docker could not create replacement container {name}.") from error
        finally:
            connection.close()
        if response.status != 201:
            raise MigrationError(f"Docker rejected replacement container {name}.")


@dataclass
class MigrationPlan:
    spec: TargetSpec
    inspected: dict[str, Any]
    payload: dict[str, Any]
    already_compliant: bool
    backup_name: str
    volume_name: str
    original_log_driver: str
    was_running: bool


def _required_mapping(value: Any, field: str, name: str) -> Mapping[str, Any]:
    if not isinstance(value, Mapping):
        raise MigrationError(f"{name} has invalid Docker {field} configuration.")
    return value


def _volume_identity(inspected: Mapping[str, Any], spec: TargetSpec) -> dict[str, Any]:
    mounts = inspected.get("Mounts")
    if not isinstance(mounts, list) or len(mounts) != 1:
        raise MigrationError(f"{spec.name} must have exactly one audited Docker volume mount.")
    mount = mounts[0]
    if not isinstance(mount, Mapping):
        raise MigrationError(f"{spec.name} has invalid Docker mount configuration.")
    if (
        mount.get("Type") != "volume"
        or not isinstance(mount.get("Name"), str)
        or not mount.get("Name")
        or mount.get("Destination") != spec.volume_destination
        or mount.get("RW") is not True
    ):
        raise MigrationError(f"{spec.name} does not use its audited Docker volume identity.")
    return {
        "Type": "volume",
        "Name": mount["Name"],
        "Destination": spec.volume_destination,
        "RW": True,
    }


def _user_network_aliases(
    endpoint: Mapping[str, Any],
    *,
    container_id: str,
    container_name: str,
) -> list[str]:
    automatic = {container_id, container_id[:12], container_name}
    aliases = endpoint.get("Aliases") or []
    if not isinstance(aliases, list) or not all(isinstance(alias, str) for alias in aliases):
        raise MigrationError(f"{container_name} has invalid Docker network aliases.")
    return [alias for alias in aliases if alias not in automatic]


def _networking_payload(inspected: Mapping[str, Any], spec: TargetSpec) -> dict[str, Any]:
    settings = _required_mapping(inspected.get("NetworkSettings"), "network", spec.name)
    networks = _required_mapping(settings.get("Networks"), "network", spec.name)
    if not networks:
        raise MigrationError(f"{spec.name} has no Docker network membership to preserve.")

    container_id = str(inspected.get("Id") or "")
    endpoints: dict[str, Any] = {}
    for network_name, raw_endpoint in networks.items():
        if not isinstance(network_name, str) or not isinstance(raw_endpoint, Mapping):
            raise MigrationError(f"{spec.name} has invalid Docker network configuration.")
        if raw_endpoint.get("IPAMConfig") not in (None, {}):
            raise MigrationError(f"{spec.name} uses unsupported static Docker network addressing.")
        endpoint: dict[str, Any] = {}
        aliases = _user_network_aliases(
            raw_endpoint,
            container_id=container_id,
            container_name=spec.name,
        )
        if aliases:
            endpoint["Aliases"] = aliases
        if raw_endpoint.get("Links"):
            endpoint["Links"] = copy.deepcopy(raw_endpoint["Links"])
        if raw_endpoint.get("DriverOpts"):
            endpoint["DriverOpts"] = copy.deepcopy(raw_endpoint["DriverOpts"])
        endpoints[network_name] = endpoint
    return {"EndpointsConfig": endpoints}


def _is_compliant_log_config(log_config: Mapping[str, Any]) -> bool:
    return dict(log_config) == BOUNDED_LOG_CONFIG


def _build_plan(
    inspected: dict[str, Any],
    spec: TargetSpec,
    *,
    backup_name: str,
) -> MigrationPlan:
    inspected_name = str(inspected.get("Name") or "").removeprefix("/")
    if inspected_name != spec.name:
        raise MigrationError(f"Docker inspect target did not match {spec.name}.")

    config = _required_mapping(inspected.get("Config"), "container", spec.name)
    host_config = _required_mapping(inspected.get("HostConfig"), "host", spec.name)
    labels = _required_mapping(config.get("Labels"), "label", spec.name)
    if labels.get("testzone.managed") != "true":
        raise MigrationError(f"{spec.name} is not an audited TestZone-managed container.")
    if config.get("Image") not in spec.images:
        raise MigrationError(f"{spec.name} uses an image outside the audited allowlist.")
    if host_config.get("AutoRemove") is True:
        raise MigrationError(f"{spec.name} uses unsafe Docker auto-removal.")

    log_config = _required_mapping(host_config.get("LogConfig"), "logging", spec.name)
    log_type = log_config.get("Type")
    if log_type == "local" and not _is_compliant_log_config(log_config):
        raise MigrationError(f"{spec.name} has a noncompliant local log configuration.")
    if log_type not in ("json-file", "local"):
        raise MigrationError(f"{spec.name} uses a logging driver outside the audited migration scope.")

    state = _required_mapping(inspected.get("State"), "state", spec.name)
    if not isinstance(state.get("Running"), bool):
        raise MigrationError(f"{spec.name} has invalid Docker running-state metadata.")

    volume_identity = _volume_identity(inspected, spec)
    networking_config = _networking_payload(inspected, spec)

    immutable_image = inspected.get("Image")
    if not isinstance(immutable_image, str) or not re.fullmatch(r"sha256:[0-9a-f]{64}", immutable_image):
        raise MigrationError(f"{spec.name} does not expose an immutable Docker image identity.")

    replacement_config = copy.deepcopy(dict(config))
    replacement_config["Image"] = immutable_image
    replacement_host_config = copy.deepcopy(dict(host_config))
    replacement_host_config["LogConfig"] = copy.deepcopy(BOUNDED_LOG_CONFIG)
    # Image-declared anonymous volumes are absent from HostConfig.Binds/Mounts
    # even though inspect.Mounts exposes their identity. Submit the captured
    # volume explicitly so Docker cannot allocate an empty replacement volume.
    replacement_host_config["Binds"] = None
    replacement_host_config["Mounts"] = [
        {
            "Type": "volume",
            "Source": volume_identity["Name"],
            "Target": spec.volume_destination,
            "ReadOnly": False,
        }
    ]
    payload = replacement_config
    payload["HostConfig"] = replacement_host_config
    payload["NetworkingConfig"] = networking_config
    return MigrationPlan(
        spec=spec,
        inspected=inspected,
        payload=payload,
        already_compliant=_is_compliant_log_config(log_config),
        backup_name=backup_name,
        volume_name=volume_identity["Name"],
        original_log_driver=str(log_type),
        was_running=state["Running"],
    )


def _normalized_networks(inspected: Mapping[str, Any], spec: TargetSpec) -> dict[str, list[str]]:
    settings = _required_mapping(inspected.get("NetworkSettings"), "network", spec.name)
    networks = _required_mapping(settings.get("Networks"), "network", spec.name)
    container_id = str(inspected.get("Id") or "")
    return {
        network_name: _user_network_aliases(
            endpoint,
            container_id=container_id,
            container_name=spec.name,
        )
        for network_name, endpoint in networks.items()
    }


def _verify_replacement(plan: MigrationPlan, replacement: Mapping[str, Any]) -> None:
    expected_config = {
        key: copy.deepcopy(value)
        for key, value in plan.payload.items()
        if key not in ("HostConfig", "NetworkingConfig")
    }
    actual_config = _required_mapping(
        replacement.get("Config"),
        "replacement container",
        plan.spec.name,
    )
    if dict(actual_config) != expected_config:
        raise MigrationError(f"{plan.spec.name} replacement config verification failed.")

    expected_host = _required_mapping(plan.payload.get("HostConfig"), "submitted host", plan.spec.name)
    actual_host = _required_mapping(replacement.get("HostConfig"), "replacement host", plan.spec.name)
    required_host_keys = (
        "NetworkMode",
        "PortBindings",
        "RestartPolicy",
        "LogConfig",
        *RESOURCE_KEYS,
    )
    for key in required_host_keys:
        expected_value = expected_host.get(key)
        actual_value = actual_host.get(key)
        # Docker normalizes an omitted OOM-kill flag to the explicit default
        # `false` when a container is recreated. Both mean OOM killing remains
        # enabled, so this is not a resource-policy change.
        if key == "OomKillDisable":
            expected_value = bool(expected_value)
            actual_value = bool(actual_value)
        if actual_value != expected_value:
            raise MigrationError(
                f"{plan.spec.name} replacement host config verification failed for {key}."
            )

    if _normalized_networks(replacement, plan.spec) != _normalized_networks(
        plan.inspected,
        plan.spec,
    ):
        raise MigrationError(f"{plan.spec.name} replacement network verification failed.")
    replacement_volume = _volume_identity(replacement, plan.spec)
    if replacement_volume["Name"] != plan.volume_name:
        raise MigrationError(f"{plan.spec.name} replacement volume verification failed.")


def _safe_inspect(runtime: Any, name: str) -> dict[str, Any] | None:
    try:
        return runtime.inspect(name, allow_missing=True)
    except MigrationError:
        return None


def _rollback(runtime: Any, changed: Sequence[MigrationPlan]) -> None:
    rollback_failed = False
    for plan in reversed(changed):
        original_exists = _safe_inspect(runtime, plan.spec.name) is not None
        backup_exists = _safe_inspect(runtime, plan.backup_name) is not None
        if backup_exists and original_exists:
            try:
                runtime.remove(plan.spec.name, force=True)
                original_exists = False
            except MigrationError:
                rollback_failed = True
                continue
        if backup_exists:
            try:
                runtime.rename(plan.backup_name, plan.spec.name)
                original_exists = True
            except MigrationError:
                rollback_failed = True
                continue
        if not original_exists:
            rollback_failed = True
            continue
        if plan.was_running:
            try:
                runtime.start(plan.spec.name)
            except MigrationError:
                rollback_failed = True
    if rollback_failed:
        raise MigrationError("TestZone logging migration failed and automatic rollback was incomplete.")


def _validated_run_id(run_id: str | None) -> str:
    value = str(run_id or os.environ.get("GITHUB_RUN_ID") or "")
    if not re.fullmatch(r"[0-9]+", value):
        raise MigrationError("GITHUB_RUN_ID is required for the audited backup names.")
    return value


def build_plans(runtime: Any, *, run_id: str | None = None) -> list[MigrationPlan]:
    runtime.ensure_ready()
    safe_run_id = _validated_run_id(run_id)

    plans: list[MigrationPlan] = []
    for spec in TARGETS:
        backup_prefix = f"{spec.name}-logging-backup-"
        if runtime.list_names(backup_prefix):
            raise MigrationError(
                f"A stale migration backup must be reviewed before changing {spec.name}."
            )
        backup_name = f"{spec.name}-logging-backup-{safe_run_id}"
        inspected = runtime.inspect(spec.name)
        if inspected is None:
            raise MigrationError(f"Required TestZone container is unavailable: {spec.name}")
        plans.append(_build_plan(inspected, spec, backup_name=backup_name))
    return plans


def preflight(runtime: Any, *, run_id: str | None = None) -> dict[str, Any]:
    plans = build_plans(runtime, run_id=run_id)
    return {
        "schemaVersion": 1,
        "mode": "preflight",
        "targets": [
            {
                "name": plan.spec.name,
                "status": (
                    "already-compliant" if plan.already_compliant else "requires-migration"
                ),
                "volumeName": plan.volume_name,
                "logDriver": plan.original_log_driver,
                "wasRunning": plan.was_running,
            }
            for plan in plans
        ],
    }


def migrate(runtime: Any, *, run_id: str | None = None) -> dict[str, Any]:
    plans = build_plans(runtime, run_id=run_id)

    changed: list[MigrationPlan] = []
    try:
        for plan in plans:
            if plan.already_compliant:
                continue
            changed.append(plan)
            print(f"{plan.spec.name}: stopping the original container.")
            runtime.stop(plan.spec.name)
            print(f"{plan.spec.name}: reserving a rollback backup name.")
            runtime.rename(plan.spec.name, plan.backup_name)
            print(f"{plan.spec.name}: submitting the bounded-log replacement.")
            runtime.create(plan.spec.name, plan.payload)
            replacement = runtime.inspect(plan.spec.name)
            if replacement is None:
                raise MigrationError(f"Replacement container was not created: {plan.spec.name}")
            print(f"{plan.spec.name}: verifying submitted configuration and volume identity.")
            _verify_replacement(plan, replacement)
            if plan.was_running:
                print(f"{plan.spec.name}: submitting container start.")
                runtime.start(plan.spec.name)
    except Exception as error:
        safe_detail = (
            str(error)
            if isinstance(error, MigrationError)
            else "an unexpected migration operation failed"
        )
        try:
            _rollback(runtime, changed)
        except MigrationError:
            raise
        raise MigrationError(
            "TestZone logging migration failed; original containers were restored. "
            f"Safe failure detail: {safe_detail}"
        ) from error

    for plan in changed:
        runtime.remove(plan.backup_name)

    changed_names = {plan.spec.name for plan in changed}
    return {
        "schemaVersion": 1,
        "mode": "apply",
        "targets": [
            {
                "name": plan.spec.name,
                "status": "migrated" if plan.spec.name in changed_names else "already-compliant",
                "volumeName": plan.volume_name,
                "logDriver": "local",
                "wasRunning": plan.was_running,
            }
            for plan in plans
        ],
    }


def write_report(report: Mapping[str, Any], path: Path) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(report, sort_keys=True) + "\n", encoding="utf-8")


def render_summary(report_path: Path, status: str) -> str:
    target_names = ", ".join(f"`{spec.name}`" for spec in TARGETS)
    migrated: list[str] = []
    migrated_without_restart: list[str] = []
    already_compliant: list[str] = []
    requires_migration: list[str] = []
    volume_names: list[str] = []
    log_drivers: list[str] = []
    report_mode = "unavailable"
    if report_path.is_file():
        try:
            report = json.loads(report_path.read_text(encoding="utf-8"))
            if report.get("mode") in ("preflight", "apply"):
                report_mode = str(report["mode"])
            targets = report.get("targets", [])
            if isinstance(targets, list):
                migrated = [
                    str(target.get("name"))
                    for target in targets
                    if isinstance(target, Mapping)
                    and target.get("status") == "migrated"
                    and target.get("wasRunning") is True
                ]
                migrated_without_restart = [
                    str(target.get("name"))
                    for target in targets
                    if isinstance(target, Mapping)
                    and target.get("status") == "migrated"
                    and target.get("wasRunning") is False
                ]
                already_compliant = [
                    str(target.get("name"))
                    for target in targets
                    if isinstance(target, Mapping) and target.get("status") == "already-compliant"
                ]
                requires_migration = [
                    str(target.get("name"))
                    for target in targets
                    if isinstance(target, Mapping) and target.get("status") == "requires-migration"
                ]
                reported_volumes = [
                    str(target.get("volumeName"))
                    for target in targets
                    if isinstance(target, Mapping)
                    and isinstance(target.get("volumeName"), str)
                    and target.get("volumeName")
                ]
                if len(reported_volumes) == len(TARGETS):
                    volume_names = reported_volumes
                reported_log_drivers = [
                    f"`{target.get('name')}`: `{target.get('logDriver')}`"
                    for target in targets
                    if isinstance(target, Mapping)
                    and target.get("name") in {spec.name for spec in TARGETS}
                    and target.get("logDriver") in ("json-file", "local")
                ]
                if len(reported_log_drivers) == len(TARGETS):
                    log_drivers = reported_log_drivers
        except (OSError, json.JSONDecodeError):
            pass

    volume_summary = ", ".join(f"`{name}`" for name in volume_names) or "not reported because the audited preflight did not complete"
    migrated_summary = ", ".join(f"`{name}`" for name in migrated) or "none"
    stopped_migration_summary = ", ".join(f"`{name}`" for name in migrated_without_restart) or "none"
    compliant_summary = ", ".join(f"`{name}`" for name in already_compliant) or "none"
    preflight_summary = ", ".join(f"`{name}`" for name in requires_migration) or "none"
    return "\n".join(
        (
            "## TestZone component logging audit/migration",
            "",
            f"- Result: `{status}`",
            f"- Mode: `{report_mode}`",
            f"- Exact container scope: {target_names}",
            f"- Migrated with a brief restart: {migrated_summary}",
            f"- Migrated while preserving a stopped state: {stopped_migration_summary}",
            f"- Preflight found migration required: {preflight_summary}",
            f"- Already compliant without a restart: {compliant_summary}",
            f"- Docker volume identities (audited; preserved on apply): {volume_summary}",
            f"- Reported log drivers: {', '.join(log_drivers) or 'not reported because the audited preflight did not complete'}",
            "- Target policy: Docker `local`, 10 MiB times three compressed files",
            "- Verification scope: audited/submitted container configuration and the same Docker volume mount identity only; no runtime health, HTTP, or database gate",
            "- Historical logs: successful migration removes the retired container's `json-file` history; Docker cannot recover it after removal unless it was copied or forwarded externally beforehand",
            "- Durable data: no volume was copied, removed, or pruned",
            "",
        )
    )


def parse_args(arguments: Sequence[str]) -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    subparsers = parser.add_subparsers(dest="command", required=True)
    migrate_parser = subparsers.add_parser("migrate")
    migrate_parser.add_argument("--report", required=True, type=Path)
    preflight_parser = subparsers.add_parser("preflight")
    preflight_parser.add_argument("--report", required=True, type=Path)
    summary_parser = subparsers.add_parser("render-summary")
    summary_parser.add_argument("--report", required=True, type=Path)
    summary_parser.add_argument("--status", required=True)
    return parser.parse_args(arguments)


def main(arguments: Sequence[str] | None = None) -> int:
    args = parse_args(arguments or sys.argv[1:])
    if args.command == "render-summary":
        sys.stdout.write(render_summary(args.report, args.status))
        return 0

    try:
        if args.command == "preflight":
            report = preflight(DockerRuntime())
        else:
            report = migrate(DockerRuntime())
        write_report(report, args.report)
    except MigrationError as error:
        print(str(error), file=sys.stderr)
        return 1
    if args.command == "preflight":
        print("TestZone component logging preflight completed without mutation.")
    else:
        print("TestZone component logging migration completed successfully.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
