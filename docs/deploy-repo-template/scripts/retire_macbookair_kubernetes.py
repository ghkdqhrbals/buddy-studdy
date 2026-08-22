#!/usr/bin/env python3
"""Guarded retirement of BuddyStudy's legacy Docker Desktop Kubernetes runtime.

The helper deliberately has no resource-delete, Docker reset, volume removal, or
prune operation.  Backup payloads can contain Kubernetes Secrets, database
contents, and Redis data, so command failures and reports stay secret-free.
"""

from __future__ import annotations

import argparse
import gzip
import hashlib
import hmac
import json
import os
import platform
import re
import signal
import shutil
import subprocess
import sys
import tarfile
import tempfile
import threading
import time
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Mapping, Sequence


KUBE_CONTEXT = "docker-desktop"
NAMESPACE = "buddystudy"
CONFIRMATION = "RETIRE DOCKER DESKTOP KUBERNETES"
SYSTEM_NAMESPACES = frozenset(("kube-system", "kube-public", "kube-node-lease"))
ALLOWED_AUXILIARY_WORKLOADS = frozenset(
    (("default", "Deployment", "buddystudy-redis-stream-coordinator"),)
)
SETTINGS_KEYS = ("KubernetesEnabled", "kubernetesEnabled")
SETTINGS_RELATIVE_PATHS = (
    Path("Library/Group Containers/group.com.docker/settings-store.json"),
    Path("Library/Group Containers/group.com.docker/settings.json"),
)
DOCKER_DATA_RELATIVE_PATH = Path("Library/Containers/com.docker.docker/Data")
DEFAULT_BACKUP_RELATIVE_PATH = Path(
    "Library/Application Support/BuddyStudy/KubernetesRetirementBackups"
)
NAMESPACED_RESOURCES = (
    "deployments.apps",
    "statefulsets.apps",
    "daemonsets.apps",
    "cronjobs.batch",
    "services",
    "configmaps",
    "secrets",
    "serviceaccounts",
    "roles.rbac.authorization.k8s.io",
    "rolebindings.rbac.authorization.k8s.io",
    "persistentvolumeclaims",
)
WORKLOAD_RESOURCES = (
    "deployments.apps",
    "statefulsets.apps",
    "daemonsets.apps",
    "cronjobs.batch",
)
DATABASE_IMAGE_PATTERNS = {
    "postgresql": re.compile(r"(?:^|[/:-])postgres(?:$|[:@-])", re.IGNORECASE),
    "mysql": re.compile(r"(?:^|[/:-])(?:mysql|mariadb)(?:$|[:@-])", re.IGNORECASE),
    "redis": re.compile(r"(?:^|[/:-])redis(?:$|[:@-])", re.IGNORECASE),
}
MINIMUM_BACKUP_FREE_BYTES = 12 * 1024 * 1024 * 1024
KEYCHAIN_SERVICE = "BuddyStudy MacBook Air Kubernetes Retirement Backup"
KEYCHAIN_ACCOUNT = "buddystudy-kubernetes-retirement"


class RetirementError(RuntimeError):
    """Secret-free retirement failure."""


_SIGNAL_GUARD_ACTIVE = False


def _handle_termination(signum: int, _frame: Any) -> None:
    raise RetirementError(f"Retirement interrupted by signal {signum}; rollback requested.")


def _install_termination_guard() -> None:
    global _SIGNAL_GUARD_ACTIVE
    signal.signal(signal.SIGTERM, _handle_termination)
    signal.signal(signal.SIGINT, _handle_termination)
    _SIGNAL_GUARD_ACTIVE = True


def _protect_rollback_from_additional_signals() -> None:
    if _SIGNAL_GUARD_ACTIVE:
        signal.signal(signal.SIGTERM, signal.SIG_IGN)
        signal.signal(signal.SIGINT, signal.SIG_IGN)


def _safe_name(value: str) -> str:
    safe = re.sub(r"[^a-zA-Z0-9_.-]+", "-", value).strip(".-")
    if not safe:
        raise RetirementError("A Kubernetes resource has an unsafe empty name.")
    return safe


def _write_private(path: Path, data: bytes) -> None:
    path.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
    descriptor = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    try:
        with os.fdopen(descriptor, "wb") as handle:
            handle.write(data)
            handle.flush()
            os.fsync(handle.fileno())
    except Exception:
        try:
            os.close(descriptor)
        except OSError:
            pass
        raise


def _write_json(path: Path, value: Any) -> None:
    _write_private(
        path,
        (json.dumps(value, ensure_ascii=False, indent=2, sort_keys=True) + "\n").encode(
            "utf-8"
        ),
    )


def _terminate_children(*processes: subprocess.Popen[Any] | None) -> None:
    for process in processes:
        if process is None:
            continue
        try:
            running = process.poll() is None
        except (OSError, subprocess.SubprocessError):
            running = True
        if running:
            try:
                process.kill()
            except OSError:
                pass
    for process in processes:
        if process is not None:
            try:
                process.wait(timeout=30)
            except (OSError, subprocess.SubprocessError):
                pass


def _read_json(path: Path, description: str) -> Any:
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        raise RetirementError(f"Could not read valid {description} JSON.") from error


def _sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for block in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


class CommandRunner:
    """Runs local commands without echoing stdout, stderr, or command secrets."""

    def run(
        self,
        arguments: Sequence[str],
        *,
        timeout: int = 60,
        check: bool = True,
        input_bytes: bytes | None = None,
    ) -> subprocess.CompletedProcess[bytes]:
        try:
            result = subprocess.run(
                list(arguments),
                input=input_bytes,
                stdout=subprocess.PIPE,
                stderr=subprocess.PIPE,
                timeout=timeout,
                check=False,
            )
        except (OSError, subprocess.SubprocessError) as error:
            raise RetirementError("A guarded local command could not be executed.") from error
        if check and result.returncode != 0:
            raise RetirementError("A guarded local command failed; output was suppressed.")
        return result

    def stream_to_gzip(
        self,
        arguments: Sequence[str],
        destination: Path,
        *,
        timeout: int,
    ) -> None:
        destination.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
        stderr_file = tempfile.TemporaryFile()
        process: subprocess.Popen[bytes] | None = None
        timer: threading.Timer | None = None
        try:
            process = subprocess.Popen(
                list(arguments),
                stdout=subprocess.PIPE,
                stderr=stderr_file,
            )
            timed_out = threading.Event()

            def terminate_on_timeout() -> None:
                timed_out.set()
                try:
                    if process is not None and process.poll() is None:
                        process.kill()
                except OSError:
                    pass

            timer = threading.Timer(timeout, terminate_on_timeout)
            timer.daemon = True
            timer.start()
            assert process.stdout is not None
            with process.stdout, gzip.open(
                destination, "wb", compresslevel=6
            ) as compressed:
                shutil.copyfileobj(process.stdout, compressed, length=1024 * 1024)
            return_code = process.wait()
            if timed_out.is_set():
                raise RetirementError("A guarded backup command timed out.")
            if return_code != 0:
                raise RetirementError("A guarded backup command failed; output was suppressed.")
            os.chmod(destination, 0o600)
        except (OSError, subprocess.SubprocessError) as error:
            _terminate_children(process)
            raise RetirementError("A guarded backup command could not be executed.") from error
        except BaseException:
            _terminate_children(process)
            raise
        finally:
            if timer is not None:
                timer.cancel()
            stderr_file.close()

    def stream_raw(
        self,
        arguments: Sequence[str],
        destination: Path,
        *,
        timeout: int,
    ) -> None:
        destination.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
        stderr_file = tempfile.TemporaryFile()
        process: subprocess.Popen[bytes] | None = None
        try:
            with destination.open("wb") as output:
                process = subprocess.Popen(
                    list(arguments), stdout=output, stderr=stderr_file
                )
                try:
                    return_code = process.wait(timeout=timeout)
                except subprocess.TimeoutExpired as error:
                    process.kill()
                    process.wait()
                    raise RetirementError("A guarded archive command timed out.") from error
                if return_code != 0:
                    raise RetirementError(
                        "A guarded archive command failed; output was suppressed."
                    )
                output.flush()
                os.fsync(output.fileno())
            os.chmod(destination, 0o600)
        except (OSError, subprocess.SubprocessError) as error:
            _terminate_children(process)
            raise RetirementError("A guarded archive command could not be executed.") from error
        except BaseException:
            _terminate_children(process)
            raise
        finally:
            stderr_file.close()


class KubernetesRuntime:
    def __init__(self, runner: CommandRunner) -> None:
        self.runner = runner

    def _arguments(
        self,
        arguments: Sequence[str],
        *,
        namespaced: bool = True,
        namespace: str = NAMESPACE,
    ) -> list[str]:
        command = ["kubectl", "--context", KUBE_CONTEXT]
        if namespaced:
            if namespace not in (NAMESPACE, "default"):
                raise RetirementError("A Kubernetes command targeted an unapproved namespace.")
            command.extend(("--namespace", namespace))
        command.extend(arguments)
        return command

    def run(
        self,
        arguments: Sequence[str],
        *,
        namespaced: bool = True,
        namespace: str = NAMESPACE,
        timeout: int = 60,
        check: bool = True,
    ) -> subprocess.CompletedProcess[bytes]:
        return self.runner.run(
            self._arguments(
                arguments, namespaced=namespaced, namespace=namespace
            ),
            timeout=timeout,
            check=check,
        )

    def json(
        self,
        arguments: Sequence[str],
        *,
        namespaced: bool = True,
        namespace: str = NAMESPACE,
        timeout: int = 60,
    ) -> Any:
        result = self.run(
            arguments,
            namespaced=namespaced,
            namespace=namespace,
            timeout=timeout,
            check=True,
        )
        try:
            return json.loads(result.stdout)
        except json.JSONDecodeError as error:
            raise RetirementError("Kubernetes returned invalid JSON.") from error

    def ensure_exact_target(self) -> dict[str, Any]:
        config = self.json(
            ("config", "view", "--minify", "--raw", "-o", "json"),
            namespaced=False,
        )
        contexts = config.get("contexts") if isinstance(config, Mapping) else None
        clusters = config.get("clusters") if isinstance(config, Mapping) else None
        current = config.get("current-context") if isinstance(config, Mapping) else None
        if (
            current != KUBE_CONTEXT
            or not isinstance(contexts, list)
            or len(contexts) != 1
            or not isinstance(clusters, list)
            or len(clusters) != 1
        ):
            raise RetirementError(
                "kubectl did not resolve exactly the docker-desktop context."
            )
        context = contexts[0].get("context", {})
        if context.get("namespace") not in (None, "", NAMESPACE):
            raise RetirementError("The Docker Desktop context selects another namespace.")
        server = clusters[0].get("cluster", {}).get("server")
        if server not in (
            "https://127.0.0.1:6443",
            "https://localhost:6443",
        ):
            raise RetirementError(
                "The docker-desktop context does not use the audited local API server."
            )

        namespace = self.json(("get", "namespace", NAMESPACE, "-o", "json"), namespaced=False)
        metadata = namespace.get("metadata") if isinstance(namespace, Mapping) else None
        if not isinstance(metadata, Mapping) or metadata.get("name") != NAMESPACE:
            raise RetirementError("The exact BuddyStudy namespace is unavailable.")
        return namespace

    def list_resource(
        self,
        resource: str,
        *,
        all_namespaces: bool = False,
        namespace: str = NAMESPACE,
    ) -> dict[str, Any]:
        arguments = ["get", resource]
        namespaced = True
        if all_namespaces:
            arguments.append("--all-namespaces")
            namespaced = False
        arguments.extend(("-o", "json"))
        document = self.json(
            arguments, namespaced=namespaced, namespace=namespace
        )
        if not isinstance(document, dict) or not isinstance(document.get("items"), list):
            raise RetirementError(f"Kubernetes returned invalid {resource} inventory.")
        return document

    def get_pv(self, name: str) -> dict[str, Any]:
        document = self.json(
            ("get", "persistentvolume", name, "-o", "json"), namespaced=False
        )
        if not isinstance(document, dict):
            raise RetirementError("Kubernetes returned invalid persistent-volume data.")
        return document

    def patch(
        self,
        resource: str,
        name: str,
        patch: Mapping[str, Any],
        *,
        namespace: str = NAMESPACE,
    ) -> None:
        self.run(
            (
                "patch",
                resource,
                name,
                "--type",
                "merge",
                "--patch",
                json.dumps(patch, separators=(",", ":")),
            ),
            namespace=namespace,
            timeout=60,
        )

    def pod_exists(self, name: str, *, namespace: str = NAMESPACE) -> bool:
        result = self.run(
            ("get", "pod", name, "-o", "name"),
            namespace=namespace,
            check=False,
        )
        return result.returncode == 0

    def exec_arguments(
        self,
        pod: str,
        container: str,
        remote_arguments: Sequence[str],
        *,
        namespace: str = NAMESPACE,
    ) -> list[str]:
        return self._arguments(
            ("exec", pod, "--container", container, "--", *remote_arguments),
            namespace=namespace,
        )


@dataclass(frozen=True)
class SettingsTarget:
    path: Path
    key: str
    enabled: bool


@dataclass(frozen=True)
class DatabaseTarget:
    kind: str
    pod: str
    container: str


@dataclass(frozen=True)
class PvcSource:
    claim: str
    pv: str
    host_path: Path | None


def discover_settings(home: Path) -> SettingsTarget:
    candidates = [home / relative for relative in SETTINGS_RELATIVE_PATHS]
    existing = [path for path in candidates if path.is_file()]
    if not existing:
        raise RetirementError("Docker Desktop settings-store was not found.")

    matches: list[SettingsTarget] = []
    for path in existing:
        _reject_symlink_components(path)
        document = _read_json(path, "Docker Desktop settings")
        if not isinstance(document, Mapping):
            raise RetirementError("Docker Desktop settings are not a JSON object.")
        present = [key for key in SETTINGS_KEYS if key in document]
        if len(present) > 1:
            raise RetirementError("Docker Desktop has ambiguous Kubernetes settings keys.")
        if present:
            value = document[present[0]]
            if not isinstance(value, bool):
                raise RetirementError("Docker Desktop Kubernetes setting is not boolean.")
            matches.append(SettingsTarget(path, present[0], value))
    if len(matches) != 1:
        raise RetirementError(
            "Exactly one existing Docker Desktop Kubernetes settings key is required."
        )
    return matches[0]


def atomically_set_kubernetes_enabled(target: SettingsTarget, enabled: bool) -> None:
    document = _read_json(target.path, "Docker Desktop settings")
    if not isinstance(document, dict) or target.key not in document:
        raise RetirementError("Docker Desktop Kubernetes settings changed unexpectedly.")
    if not isinstance(document[target.key], bool):
        raise RetirementError("Docker Desktop Kubernetes setting is not boolean.")
    document[target.key] = enabled
    original_mode = target.path.stat().st_mode & 0o777
    descriptor, temporary_name = tempfile.mkstemp(
        prefix=f".{target.path.name}.", suffix=".tmp", dir=target.path.parent
    )
    temporary = Path(temporary_name)
    try:
        with os.fdopen(descriptor, "wb") as handle:
            handle.write(
                (json.dumps(document, ensure_ascii=False, indent=2) + "\n").encode(
                    "utf-8"
                )
            )
            handle.flush()
            os.fsync(handle.fileno())
        os.chmod(temporary, original_mode)
        os.replace(temporary, target.path)
        directory_fd = os.open(target.path.parent, os.O_RDONLY)
        try:
            os.fsync(directory_fd)
        finally:
            os.close(directory_fd)
    finally:
        if temporary.exists():
            temporary.unlink()


def atomically_restore_bytes(data: bytes, mode: int, destination: Path) -> None:
    descriptor, temporary_name = tempfile.mkstemp(
        prefix=f".{destination.name}.", suffix=".restore", dir=destination.parent
    )
    temporary = Path(temporary_name)
    try:
        with os.fdopen(descriptor, "wb") as handle:
            handle.write(data)
            handle.flush()
            os.fsync(handle.fileno())
        os.chmod(temporary, mode)
        os.replace(temporary, destination)
        directory_fd = os.open(destination.parent, os.O_RDONLY)
        try:
            os.fsync(directory_fd)
        finally:
            os.close(directory_fd)
    finally:
        if temporary.exists():
            temporary.unlink()


def _metadata_identity(item: Mapping[str, Any]) -> dict[str, Any]:
    metadata = item.get("metadata")
    if not isinstance(metadata, Mapping):
        raise RetirementError("A Kubernetes manifest has invalid metadata.")
    return {
        "apiVersion": item.get("apiVersion"),
        "kind": item.get("kind"),
        "namespace": metadata.get("namespace"),
        "name": metadata.get("name"),
        "uid": metadata.get("uid"),
        "resourceVersion": metadata.get("resourceVersion"),
        "generation": metadata.get("generation"),
        "spec": item.get("spec"),
        "data": item.get("data") if item.get("kind") in ("Secret", "ConfigMap") else None,
        "binaryData": item.get("binaryData") if item.get("kind") == "ConfigMap" else None,
    }


def inventory_digest(
    namespace: Mapping[str, Any],
    resources: Mapping[str, Mapping[str, Any]],
    pvs: Sequence[Mapping[str, Any]],
    auxiliary_workloads: Sequence[Mapping[str, Any]],
) -> str:
    stable: list[dict[str, Any]] = []
    stable.append(_metadata_identity(namespace))
    for resource in sorted(resources):
        for item in resources[resource].get("items", []):
            stable.append(_metadata_identity(item))
    for pv in pvs:
        stable.append(_metadata_identity(pv))
    for workload in auxiliary_workloads:
        stable.append(_metadata_identity(workload))
    encoded = json.dumps(
        stable, ensure_ascii=False, sort_keys=True, separators=(",", ":")
    ).encode("utf-8")
    return hashlib.sha256(encoded).hexdigest()


def collect_inventory(cluster: KubernetesRuntime) -> dict[str, Any]:
    namespace_manifest = cluster.ensure_exact_target()
    resources = {
        resource: cluster.list_resource(resource) for resource in NAMESPACED_RESOURCES
    }
    pv_names: list[str] = []
    for pvc in resources["persistentvolumeclaims"]["items"]:
        volume_name = pvc.get("spec", {}).get("volumeName")
        if not isinstance(volume_name, str) or not volume_name:
            name = pvc.get("metadata", {}).get("name", "unknown")
            raise RetirementError(f"PVC {name} is not bound to an exact persistent volume.")
        pv_names.append(volume_name)
    if len(set(pv_names)) != len(pv_names):
        raise RetirementError("Multiple PVCs unexpectedly resolve to one persistent volume.")
    pvs = [cluster.get_pv(name) for name in sorted(pv_names)]

    foreign = cluster.list_resource(
        ",".join((*WORKLOAD_RESOURCES, "replicationcontrollers", "jobs.batch")),
        all_namespaces=True,
    )["items"]
    foreign_user_workloads = []
    auxiliary_workloads = []
    active_jobs = []
    unsupported_target_workloads = []
    for item in foreign:
        item_namespace = item.get("metadata", {}).get("namespace")
        identity = (
            item_namespace,
            item.get("kind"),
            item.get("metadata", {}).get("name"),
        )
        if identity in ALLOWED_AUXILIARY_WORKLOADS:
            auxiliary_workloads.append(item)
        elif item_namespace == NAMESPACE:
            if item.get("kind") == "DaemonSet":
                unsupported_target_workloads.append(
                    {
                        "kind": item.get("kind"),
                        "namespace": item_namespace,
                        "name": item.get("metadata", {}).get("name"),
                    }
                )
            elif item.get("kind") == "ReplicationController":
                unsupported_target_workloads.append(
                    {
                        "kind": item.get("kind"),
                        "namespace": item_namespace,
                        "name": item.get("metadata", {}).get("name"),
                    }
                )
            elif item.get("kind") == "Job" and int(
                item.get("status", {}).get("active", 0) or 0
            ) > 0:
                active_jobs.append(
                    {
                        "kind": "Job",
                        "namespace": item_namespace,
                        "name": item.get("metadata", {}).get("name"),
                    }
                )
        elif item_namespace not in SYSTEM_NAMESPACES:
            if item.get("kind") == "Job" and int(
                item.get("status", {}).get("active", 0) or 0
            ) == 0:
                continue
            foreign_user_workloads.append(
                {
                    "kind": item.get("kind"),
                    "namespace": item_namespace,
                    "name": item.get("metadata", {}).get("name"),
                }
            )

    pods = cluster.list_resource("pods")
    auxiliary_pods = cluster.list_resource("pods", namespace="default")
    replica_sets = cluster.list_resource("replicasets.apps", all_namespaces=True)[
        "items"
    ]
    allowed_deployments = {
        (
            NAMESPACE,
            item.get("metadata", {}).get("name"),
            item.get("metadata", {}).get("uid"),
        )
        for item in resources["deployments.apps"]["items"]
    }
    allowed_deployments.update(
        (
            item.get("metadata", {}).get("namespace"),
            item.get("metadata", {}).get("name"),
            item.get("metadata", {}).get("uid"),
        )
        for item in auxiliary_workloads
        if item.get("kind") == "Deployment"
    )
    allowed_replica_sets: set[tuple[Any, Any, Any]] = set()
    unknown_replica_sets = []
    for replica_set in replica_sets:
        metadata = replica_set.get("metadata", {})
        item_namespace = metadata.get("namespace")
        if item_namespace in SYSTEM_NAMESPACES:
            continue
        owners = metadata.get("ownerReferences") or []
        deployment_owner = next(
            (
                (owner.get("name"), owner.get("uid"))
                for owner in owners
                if owner.get("kind") == "Deployment"
            ),
            None,
        )
        identity = (item_namespace, metadata.get("name"), metadata.get("uid"))
        if deployment_owner and (
            item_namespace, deployment_owner[0], deployment_owner[1]
        ) in allowed_deployments:
            allowed_replica_sets.add(identity)
        else:
            unknown_replica_sets.append(
                {
                    "kind": "ReplicaSet",
                    "namespace": item_namespace,
                    "name": metadata.get("name"),
                }
            )

    all_pods = cluster.list_resource("pods", all_namespaces=True)["items"]
    standalone_pods = []
    target_statefulsets = {
        (
            item.get("metadata", {}).get("name"),
            item.get("metadata", {}).get("uid"),
        )
        for item in resources["statefulsets.apps"]["items"]
    }
    for pod in all_pods:
        metadata = pod.get("metadata", {})
        item_namespace = metadata.get("namespace")
        phase = pod.get("status", {}).get("phase")
        owners = metadata.get("ownerReferences") or []
        if item_namespace in SYSTEM_NAMESPACES or phase in ("Succeeded", "Failed"):
            continue
        owner_is_allowed = any(
            (
                owner.get("kind") == "ReplicaSet"
                and (item_namespace, owner.get("name"), owner.get("uid"))
                in allowed_replica_sets
            )
            or (
                owner.get("kind") == "StatefulSet"
                and item_namespace == NAMESPACE
                and (owner.get("name"), owner.get("uid")) in target_statefulsets
            )
            or (owner.get("kind") == "Job" and item_namespace == NAMESPACE)
            for owner in owners
        )
        if not owner_is_allowed:
            standalone_pods.append(
                {
                    "kind": "Pod",
                    "namespace": item_namespace,
                    "name": metadata.get("name"),
                }
            )
    result = {
        "resources": resources,
        "namespaceManifest": namespace_manifest,
        "pvs": pvs,
        "pods": pods,
        "auxiliaryPods": auxiliary_pods,
        "auxiliaryWorkloads": auxiliary_workloads,
        "foreignUserWorkloads": foreign_user_workloads,
        "activeJobs": active_jobs,
        "standalonePods": standalone_pods,
        "unknownReplicaSets": unknown_replica_sets,
        "unsupportedTargetWorkloads": unsupported_target_workloads,
    }
    result["digest"] = inventory_digest(
        namespace_manifest, resources, pvs, auxiliary_workloads
    )
    return result


def _running_container_names(pod: Mapping[str, Any]) -> frozenset[str]:
    statuses = pod.get("status", {}).get("containerStatuses") or []
    return frozenset(
        str(status.get("name"))
        for status in statuses
        if isinstance(status, Mapping)
        and isinstance(status.get("state"), Mapping)
        and isinstance(status["state"].get("running"), Mapping)
    )


def database_targets(pods: Mapping[str, Any]) -> list[DatabaseTarget]:
    targets: list[DatabaseTarget] = []
    for pod in pods.get("items", []):
        metadata = pod.get("metadata", {})
        pod_name = metadata.get("name")
        if not isinstance(pod_name, str):
            continue
        running = _running_container_names(pod)
        for container in pod.get("spec", {}).get("containers", []):
            name = container.get("name")
            image = container.get("image")
            if not isinstance(name, str) or name not in running or not isinstance(image, str):
                continue
            matches = [
                kind for kind, pattern in DATABASE_IMAGE_PATTERNS.items() if pattern.search(image)
            ]
            if len(matches) > 1:
                raise RetirementError("A running data container has an ambiguous image.")
            if matches:
                targets.append(DatabaseTarget(matches[0], pod_name, name))
    return targets


def stopped_database_containers(pods: Mapping[str, Any]) -> list[dict[str, str]]:
    stopped = []
    for pod in pods.get("items", []):
        pod_name = pod.get("metadata", {}).get("name")
        running = _running_container_names(pod)
        for container in pod.get("spec", {}).get("containers", []):
            name = container.get("name")
            image = container.get("image")
            if not isinstance(name, str) or not isinstance(image, str):
                continue
            kind = next(
                (
                    candidate
                    for candidate, pattern in DATABASE_IMAGE_PATTERNS.items()
                    if pattern.search(image)
                ),
                None,
            )
            if kind and name not in running:
                stopped.append(
                    {"kind": kind, "pod": str(pod_name), "container": name}
                )
    return stopped


def pvc_sources(inventory: Mapping[str, Any]) -> list[PvcSource]:
    pv_by_name = {
        pv.get("metadata", {}).get("name"): pv for pv in inventory["pvs"]
    }
    result: list[PvcSource] = []
    for pvc in inventory["resources"]["persistentvolumeclaims"]["items"]:
        claim = pvc.get("metadata", {}).get("name")
        pv_name = pvc.get("spec", {}).get("volumeName")
        if not isinstance(claim, str) or not isinstance(pv_name, str):
            raise RetirementError("A PVC has invalid identity metadata.")
        pv = pv_by_name.get(pv_name)
        if not isinstance(pv, Mapping):
            raise RetirementError(f"PVC {claim} has no captured persistent volume.")
        raw_host_path = pv.get("spec", {}).get("hostPath", {}).get("path")
        host_path = Path(raw_host_path) if isinstance(raw_host_path, str) else None

        result.append(
            PvcSource(
                claim=claim,
                pv=pv_name,
                host_path=host_path,
            )
        )
    return result


def _selector_matches(selector: Mapping[str, Any], labels: Mapping[str, Any]) -> bool:
    if not selector.get("matchLabels") and not selector.get("matchExpressions"):
        return False
    for key, value in (selector.get("matchLabels") or {}).items():
        if labels.get(key) != value:
            return False
    for expression in selector.get("matchExpressions") or []:
        key = expression.get("key")
        operator = expression.get("operator")
        values = expression.get("values") or []
        if operator == "In" and labels.get(key) not in values:
            return False
        if operator == "NotIn" and labels.get(key) in values:
            return False
        if operator == "Exists" and key not in labels:
            return False
        if operator == "DoesNotExist" and key in labels:
            return False
        if operator not in ("In", "NotIn", "Exists", "DoesNotExist"):
            return False
    return True


def _pods_for_workload(
    workload: Mapping[str, Any], pods: Sequence[Mapping[str, Any]]
) -> list[str]:
    selector = workload.get("spec", {}).get("selector")
    if (
        not isinstance(selector, Mapping)
        or (not selector.get("matchLabels") and not selector.get("matchExpressions"))
    ):
        raise RetirementError("A managed workload has no auditable pod selector.")
    return [
        pod["metadata"]["name"]
        for pod in pods
        if _selector_matches(selector, pod.get("metadata", {}).get("labels", {}))
    ]


def workload_state(inventory: Mapping[str, Any]) -> dict[str, Any]:
    resources = inventory["resources"]
    pods = inventory["pods"]["items"]
    database_pods = {target.pod for target in database_targets(inventory["pods"])}
    writer_deployments: dict[str, int] = {}
    data_deployments: dict[str, int] = {}
    writer_pods: list[dict[str, str]] = []
    data_pods: list[dict[str, str]] = []
    for item in resources["deployments.apps"]["items"]:
        name = item["metadata"]["name"]
        selected_pods = _pods_for_workload(item, pods)
        target = data_deployments if database_pods.intersection(selected_pods) else writer_deployments
        target[name] = int(item.get("spec", {}).get("replicas", 1))
        pod_target = data_pods if target is data_deployments else writer_pods
        pod_target.extend({"namespace": NAMESPACE, "name": pod} for pod in selected_pods)

    auxiliary_deployments = []
    for item in inventory["auxiliaryWorkloads"]:
        if item.get("kind") != "Deployment":
            raise RetirementError("The allowed auxiliary workload changed kind.")
        name = item["metadata"]["name"]
        namespace = item["metadata"]["namespace"]
        selected_pods = _pods_for_workload(item, inventory["auxiliaryPods"]["items"])
        auxiliary_deployments.append(
            {
                "namespace": namespace,
                "name": name,
                "replicas": int(item.get("spec", {}).get("replicas", 1)),
            }
        )
        writer_pods.extend(
            {"namespace": namespace, "name": pod} for pod in selected_pods
        )

    writer_statefulsets: dict[str, int] = {}
    data_statefulsets: dict[str, int] = {}
    for item in resources["statefulsets.apps"]["items"]:
        name = item["metadata"]["name"]
        selected_pods = _pods_for_workload(item, pods)
        target = (
            data_statefulsets
            if database_pods.intersection(selected_pods)
            else writer_statefulsets
        )
        target[name] = int(item.get("spec", {}).get("replicas", 1))
        pod_target = data_pods if target is data_statefulsets else writer_pods
        pod_target.extend(
            {"namespace": NAMESPACE, "name": pod} for pod in selected_pods
        )
    return {
        "writerDeployments": writer_deployments,
        "dataDeployments": data_deployments,
        "writerStatefulsets": writer_statefulsets,
        "dataStatefulsets": data_statefulsets,
        "cronjobs": {
            item["metadata"]["name"]: bool(item.get("spec", {}).get("suspend", False))
            for item in resources["cronjobs.batch"]["items"]
        },
        "auxiliaryDeployments": auxiliary_deployments,
        "writerPods": writer_pods,
        "dataPods": data_pods,
    }


def _validate_archive(path: Path) -> None:
    if not path.is_file() or path.stat().st_size == 0:
        raise RetirementError("A required archive is empty.")
    try:
        with tarfile.open(path, "r:gz") as archive:
            for member in archive:
                if member.name.startswith("/") or ".." in Path(member.name).parts:
                    raise RetirementError("A required archive contains an unsafe path.")
    except (tarfile.TarError, OSError) as error:
        raise RetirementError("A required gzip tar archive did not verify.") from error


def _validate_gzip_contains(path: Path, markers: Sequence[bytes]) -> None:
    if not path.is_file() or path.stat().st_size == 0:
        raise RetirementError("A required logical backup is empty.")
    found = False
    try:
        with gzip.open(path, "rb") as handle:
            while block := handle.read(1024 * 1024):
                if any(marker in block for marker in markers):
                    found = True
    except (OSError, EOFError) as error:
        raise RetirementError("A required logical gzip backup did not verify.") from error
    if not found:
        raise RetirementError("A logical database backup lacks its expected dump signature.")


def _validate_redis_rdb(path: Path) -> None:
    try:
        with path.open("rb") as handle:
            header = handle.read(9)
    except OSError as error:
        raise RetirementError("The Redis RDB backup could not be verified.") from error
    if len(header) != 9 or not header.startswith(b"REDIS"):
        raise RetirementError("The Redis RDB backup has an invalid signature.")


def _safe_host_data_path(path: Path, *, home: Path, backup_root: Path) -> Path:
    if not path.is_absolute():
        raise RetirementError("A hostPath persistent volume is not absolute.")
    _reject_symlink_components(path)
    resolved = path.resolve(strict=True)
    forbidden_exact = {
        Path("/"),
        Path("/Users"),
        home.resolve(),
        Path("/System"),
        Path("/Library"),
        Path("/private"),
        Path("/var"),
    }
    if resolved in forbidden_exact or not resolved.is_dir():
        raise RetirementError("A hostPath persistent volume resolves too broadly.")
    try:
        resolved.relative_to(backup_root.resolve())
    except ValueError:
        pass
    else:
        raise RetirementError("A persistent volume points inside the retirement backup root.")
    return resolved


def _is_external_host_path(path: Path) -> bool:
    value = str(path.expanduser())
    return value.startswith("/Users/") or value.startswith("/Volumes/")


def _validate_external_host_paths(
    sources: Sequence[PvcSource], *, home: Path, backup_root: Path
) -> list[Path]:
    paths = []
    for source in sources:
        if source.host_path is None or not _is_external_host_path(source.host_path):
            continue
        expanded = source.host_path.expanduser()
        if not expanded.exists():
            raise RetirementError(
                f"External hostPath PV {source.pv} is missing on the MacBook Air."
            )
        paths.append(
            _safe_host_data_path(expanded, home=home, backup_root=backup_root)
        )
    return paths


def _reject_symlink_components(path: Path) -> None:
    absolute = path.expanduser()
    if not absolute.is_absolute():
        raise RetirementError("A guarded host path must be absolute.")
    current = Path(absolute.anchor)
    for part in absolute.parts[1:]:
        current = current / part
        if not current.exists() and not current.is_symlink():
            continue
        if current.is_symlink():
            raise RetirementError("A guarded host path contains a symbolic link.")


def _prepare_backup_directory(home: Path, backup_root: Path, run_key: str) -> Path:
    expanded = backup_root.expanduser()
    _reject_symlink_components(expanded)
    root = expanded.resolve()
    docker_data = (home / DOCKER_DATA_RELATIVE_PATH).resolve()
    try:
        root.relative_to(docker_data)
    except ValueError:
        pass
    else:
        raise RetirementError("Retirement backups must be outside Docker's Data directory.")
    root.mkdir(parents=True, exist_ok=True, mode=0o700)
    _reject_symlink_components(root)
    os.chmod(root, 0o700)
    destination = root / f"retirement-{_safe_name(run_key)}"
    if destination.exists():
        raise RetirementError("This retirement run already has a backup directory.")
    destination.mkdir(mode=0o700)
    return destination


def _backup_secret_from_environment(name: str) -> str:
    value = os.environ.get(name, "")
    if len(value) < 32:
        raise RetirementError(
            "The dedicated Kubernetes retirement backup key is missing or too short."
        )
    return value


def _ensure_keychain_recovery_key(runner: CommandRunner, secret: str) -> None:
    lookup = (
        "security",
        "find-generic-password",
        "-a",
        KEYCHAIN_ACCOUNT,
        "-s",
        KEYCHAIN_SERVICE,
        "-w",
    )
    existing = runner.run(lookup, timeout=30, check=False)
    if existing.returncode == 0:
        if not hmac.compare_digest(
            existing.stdout.rstrip(b"\r\n"), secret.encode("utf-8")
        ):
            raise RetirementError(
                "The Air Keychain recovery key does not match the Actions secret."
            )
        return
    runner.run(
        (
            "security",
            "add-generic-password",
            "-U",
            "-a",
            KEYCHAIN_ACCOUNT,
            "-s",
            KEYCHAIN_SERVICE,
            "-w",
            secret,
        ),
        timeout=30,
    )
    verified = runner.run(lookup, timeout=30)
    if not hmac.compare_digest(
        verified.stdout.rstrip(b"\r\n"), secret.encode("utf-8")
    ):
        raise RetirementError("The Air Keychain recovery key did not verify.")


def _hmac_key(secret: str) -> bytes:
    return hashlib.pbkdf2_hmac(
        "sha256",
        secret.encode("utf-8"),
        b"BuddyStudy Docker Desktop Kubernetes retirement HMAC v1",
        300_000,
        dklen=32,
    )


def _hmac_digest(path: Path, secret: str) -> str:
    digest = hmac.new(_hmac_key(secret), digestmod=hashlib.sha256)
    with path.open("rb") as handle:
        for block in iter(lambda: handle.read(4 * 1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def _seal_payload(
    payload: Path,
    encrypted: Path,
    *,
    secret: str,
    timeout: int = 2700,
) -> None:
    if not payload.is_dir() or payload.is_symlink():
        raise RetirementError("The private backup staging directory is invalid.")
    encrypted.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
    environment = os.environ.copy()
    environment["BUDDYSTUDY_RETIREMENT_BACKUP_KEY"] = secret
    tar_stderr = tempfile.TemporaryFile()
    openssl_stderr = tempfile.TemporaryFile()
    tar_process: subprocess.Popen[bytes] | None = None
    openssl_process: subprocess.Popen[bytes] | None = None
    decrypt_process: subprocess.Popen[bytes] | None = None
    list_process: subprocess.Popen[bytes] | None = None
    decrypt_stderr = None
    list_stderr = None
    try:
        with encrypted.open("wb") as output:
            tar_process = subprocess.Popen(
                ("tar", "-C", str(payload), "-cf", "-", "."),
                stdout=subprocess.PIPE,
                stderr=tar_stderr,
            )
            assert tar_process.stdout is not None
            openssl_process = subprocess.Popen(
                (
                    "openssl",
                    "enc",
                    "-aes-256-cbc",
                    "-pbkdf2",
                    "-iter",
                    "300000",
                    "-salt",
                    "-pass",
                    "env:BUDDYSTUDY_RETIREMENT_BACKUP_KEY",
                ),
                stdin=tar_process.stdout,
                stdout=output,
                stderr=openssl_stderr,
                env=environment,
            )
            tar_process.stdout.close()
            try:
                openssl_status = openssl_process.wait(timeout=timeout)
                tar_status = tar_process.wait(timeout=60)
            except subprocess.TimeoutExpired as error:
                _terminate_children(tar_process, openssl_process)
                raise RetirementError("Encrypted backup sealing timed out.") from error
            output.flush()
            os.fsync(output.fileno())
        if tar_status != 0 or openssl_status != 0 or encrypted.stat().st_size == 0:
            raise RetirementError("Encrypted backup sealing failed; output was suppressed.")
        os.chmod(encrypted, 0o600)

        expected_hmac = _hmac_digest(encrypted, secret)
        hmac_path = encrypted.with_suffix(encrypted.suffix + ".hmac")
        _write_private(hmac_path, (expected_hmac + "\n").encode("ascii"))
        if not hmac.compare_digest(_hmac_digest(encrypted, secret), expected_hmac):
            raise RetirementError("Encrypted backup HMAC verification failed.")

        decrypt_stderr = tempfile.TemporaryFile()
        list_stderr = tempfile.TemporaryFile()
        try:
            decrypt_process = subprocess.Popen(
                (
                    "openssl",
                    "enc",
                    "-d",
                    "-aes-256-cbc",
                    "-pbkdf2",
                    "-iter",
                    "300000",
                    "-pass",
                    "env:BUDDYSTUDY_RETIREMENT_BACKUP_KEY",
                    "-in",
                    str(encrypted),
                ),
                stdout=subprocess.PIPE,
                stderr=decrypt_stderr,
                env=environment,
            )
            assert decrypt_process.stdout is not None
            list_process = subprocess.Popen(
                ("tar", "-tf", "-"),
                stdin=decrypt_process.stdout,
                stdout=subprocess.DEVNULL,
                stderr=list_stderr,
            )
            decrypt_process.stdout.close()
            try:
                list_status = list_process.wait(timeout=timeout)
                decrypt_status = decrypt_process.wait(timeout=60)
            except subprocess.TimeoutExpired as error:
                _terminate_children(decrypt_process, list_process)
                raise RetirementError("Encrypted backup verification timed out.") from error
            if list_status != 0 or decrypt_status != 0:
                raise RetirementError("Encrypted backup could not be decrypted and listed.")
        finally:
            decrypt_stderr.close()
            list_stderr.close()
    except (OSError, subprocess.SubprocessError) as error:
        _terminate_children(
            tar_process, openssl_process, decrypt_process, list_process
        )
        raise RetirementError("Encrypted backup processing failed safely.") from error
    except BaseException:
        _terminate_children(
            tar_process, openssl_process, decrypt_process, list_process
        )
        raise
    finally:
        tar_stderr.close()
        openssl_stderr.close()

    shutil.rmtree(payload)


def _docker_raw_path(settings: SettingsTarget, home: Path) -> Path:
    document = _read_json(settings.path, "Docker Desktop settings")
    data_folder = document.get("DataFolder") if isinstance(document, Mapping) else None
    docker_data = (home / DOCKER_DATA_RELATIVE_PATH).resolve()
    if isinstance(data_folder, str) and data_folder:
        folder = Path(data_folder).expanduser()
        if not folder.is_absolute():
            raise RetirementError("Docker Desktop DataFolder is not absolute.")
        candidates = [folder / "Docker.raw"]
    elif data_folder is None:
        candidates = list((docker_data / "vms").glob("*/data/Docker.raw"))
        if len(candidates) != 1:
            raise RetirementError(
                "Exactly one standard Docker.raw is required when DataFolder is absent."
            )
    else:
        raise RetirementError("Docker Desktop DataFolder has an invalid type.")
    source = candidates[0]
    _reject_symlink_components(source)
    if not source.is_file() or source.is_symlink():
        raise RetirementError("Docker Desktop Docker.raw is not a regular file.")
    try:
        source.resolve().relative_to(docker_data)
    except ValueError as error:
        raise RetirementError(
            "Docker.raw is outside the audited Docker Desktop Data directory."
        ) from error
    return source.resolve()


def _restore_docker_raw_clone(
    runner: CommandRunner,
    *,
    source: Path,
    clone: Path,
    expected_hmac: str,
    destination: Path,
    secret: str,
) -> Path:
    _reject_symlink_components(source)
    _reject_symlink_components(clone)
    if not source.is_file() or source.is_symlink() or not clone.is_file() or clone.is_symlink():
        raise RetirementError("Docker.raw rollback files are unavailable.")
    if source.stat().st_dev != clone.stat().st_dev:
        raise RetirementError("Docker.raw rollback files are not on one APFS filesystem.")
    preserved = destination / "Docker.raw.failed-disabled-state"
    prepared = source.parent / ".Docker.raw.buddystudy-retirement-restore"
    if (
        preserved.exists()
        or preserved.is_symlink()
        or prepared.exists()
        or prepared.is_symlink()
    ):
        raise RetirementError("A Docker.raw rollback path already exists.")
    original_mode = source.stat().st_mode & 0o777
    moved = False
    try:
        runner.run(("/bin/cp", "-c", str(clone), str(prepared)), timeout=1800)
        os.chmod(prepared, original_mode)
        if not hmac.compare_digest(_hmac_digest(prepared, secret), expected_hmac):
            raise RetirementError("Prepared Docker.raw rollback failed HMAC verification.")
        os.replace(source, preserved)
        moved = True
        os.chmod(preserved, 0o600)
        os.replace(prepared, source)
        return preserved
    except Exception:
        if moved and preserved.exists():
            if source.exists():
                failed_restore = destination / "Docker.raw.failed-restore-output"
                if not failed_restore.exists() and not failed_restore.is_symlink():
                    os.replace(source, failed_restore)
                    os.chmod(failed_restore, 0o600)
            if not source.exists():
                os.replace(preserved, source)
                os.chmod(source, original_mode)
        if prepared.exists() and not prepared.is_symlink():
            prepared.unlink()
        raise


def _require_filevault(runner: CommandRunner) -> None:
    result = runner.run(("fdesetup", "status"), timeout=30, check=False)
    if result.returncode != 0 or b"FileVault is On" not in result.stdout:
        raise RetirementError("FileVault must protect the local Docker.raw backup.")


def _validate_backup_preconditions(
    runner: CommandRunner,
    source: Path,
    backup_root: Path,
    *,
    home: Path,
    external_paths: Sequence[Path] = (),
) -> dict[str, int]:
    expanded = backup_root.expanduser()
    _reject_symlink_components(expanded)
    docker_data = (home / DOCKER_DATA_RELATIVE_PATH).resolve()
    try:
        expanded.resolve().relative_to(docker_data)
    except ValueError:
        pass
    else:
        raise RetirementError("Retirement backup root is inside Docker's Data directory.")
    existing = expanded
    while not existing.exists() and existing != existing.parent:
        existing = existing.parent
    if existing.stat().st_dev != source.stat().st_dev:
        raise RetirementError("Retirement backup root is not on Docker.raw's APFS filesystem.")
    filesystem = runner.run(
        ("diskutil", "info", str(existing)), timeout=30, check=False
    )
    if filesystem.returncode != 0 or not re.search(
        rb"(?:Type \(Bundle\)|File System Personality):\s*APFS\b",
        filesystem.stdout,
    ):
        raise RetirementError("Retirement backup root is not on APFS.")
    external_bytes = 0
    for path in external_paths:
        result = runner.run(("du", "-sk", str(path)), timeout=600)
        try:
            kibibytes = int(result.stdout.split(maxsplit=1)[0])
        except (ValueError, IndexError) as error:
            raise RetirementError("Could not measure an external hostPath backup.") from error
        external_bytes += kibibytes * 1024
    required_free = MINIMUM_BACKUP_FREE_BYTES + (2 * external_bytes)
    available_free = shutil.disk_usage(existing).free
    if available_free < required_free:
        raise RetirementError(
            "Insufficient free APFS space for staging, ciphertext, and rollback margin."
        )
    return {
        "availableFreeBytes": available_free,
        "requiredFreeBytes": required_free,
        "externalHostPathBytes": external_bytes,
    }


def _clone_docker_raw(
    runner: CommandRunner,
    source: Path,
    destination: Path,
    *,
    secret: str,
) -> dict[str, Any]:
    _reject_symlink_components(source)
    _reject_symlink_components(destination.parent)
    filesystem = runner.run(
        ("diskutil", "info", str(source)), timeout=30, check=False
    )
    if filesystem.returncode != 0 or not re.search(
        rb"(?:Type \(Bundle\)|File System Personality):\s*APFS\b",
        filesystem.stdout,
    ):
        raise RetirementError("Docker.raw does not reside on an audited APFS filesystem.")
    source_stat = source.stat()
    parent_stat = destination.parent.stat()
    if not source.is_file() or source.is_symlink() or destination.exists():
        raise RetirementError("Docker.raw clone paths are not in the expected state.")
    if source_stat.st_dev != parent_stat.st_dev:
        raise RetirementError("Docker.raw backup must stay on the same APFS filesystem.")
    if shutil.disk_usage(destination.parent).free < 2 * 1024 * 1024 * 1024:
        raise RetirementError("At least 2 GiB free space is required before the APFS clone.")
    runner.run(("/bin/cp", "-c", str(source), str(destination)), timeout=1800)
    os.chmod(destination, 0o600)
    clone_stat = destination.stat()
    if (
        not destination.is_file()
        or destination.is_symlink()
        or clone_stat.st_dev != source_stat.st_dev
        or clone_stat.st_size != source_stat.st_size
    ):
        raise RetirementError("The APFS Docker.raw clone did not preserve file identity.")
    source_hmac = _hmac_digest(source, secret)
    raw_hmac = _hmac_digest(destination, secret)
    if not hmac.compare_digest(source_hmac, raw_hmac):
        raise RetirementError("The APFS Docker.raw clone failed byte verification.")
    _write_private(
        destination.with_suffix(destination.suffix + ".hmac"),
        (raw_hmac + "\n").encode("ascii"),
    )
    return {
        "bytes": clone_stat.st_size,
        "device": clone_stat.st_dev,
        "hmacSha256": raw_hmac,
        "copyMethod": "apfs-clone",
        "atRestProtection": "filevault",
    }


def _backup_manifests(inventory: Mapping[str, Any], destination: Path) -> None:
    manifests = destination / "manifests"
    manifests.mkdir(mode=0o700)
    _write_json(manifests / "namespace.json", inventory["namespaceManifest"])
    for resource, document in inventory["resources"].items():
        _write_json(manifests / f"{_safe_name(resource)}.json", document)
    _write_json(manifests / "persistentvolumes.json", {"items": inventory["pvs"]})
    _write_json(
        manifests / "allowed-auxiliary-workloads.json",
        {"items": inventory["auxiliaryWorkloads"]},
    )


def _remove_private_staging(payload: Path, destination: Path) -> None:
    if (
        payload.parent != destination
        or payload.name != "private-staging"
        or payload.is_symlink()
    ):
        raise RetirementError("Refusing to clean an unexpected private staging path.")
    if payload.exists():
        shutil.rmtree(payload)


def _create_private_staging(
    destination: Path,
    *,
    settings_original: bytes,
    inventory: Mapping[str, Any],
    state: Mapping[str, Any],
    settings: SettingsTarget,
    baseline: Mapping[str, Any],
) -> Path:
    payload = destination / "private-staging"
    try:
        payload.mkdir(mode=0o700)
        _write_private(
            payload / "docker-desktop-settings.original.json", settings_original
        )
        _backup_manifests(inventory, payload)
        _write_json(payload / "workload-state.json", state)
        _write_json(
            payload / "retirement-metadata.json",
            {
                "context": KUBE_CONTEXT,
                "namespace": NAMESPACE,
                "inventoryDigest": inventory["digest"],
                "settingsFile": str(settings.path),
                "settingsKey": settings.key,
                "baselineKubernetesContainers": baseline["kubernetesTotal"],
                "baselineRunningKubernetesContainers": baseline[
                    "kubernetesRunning"
                ],
                "nonKubernetesContainers": baseline["nonKubernetes"],
                "dockerVolumes": baseline["volumes"],
                "dockerNetworks": baseline["networks"],
            },
        )
        return payload
    except Exception:
        _remove_private_staging(payload, destination)
        raise


def _backup_databases(
    cluster: KubernetesRuntime,
    runner: CommandRunner,
    targets: Sequence[DatabaseTarget],
    destination: Path,
) -> list[Path]:
    backup_dir = destination / "logical-dumps"
    backup_dir.mkdir(mode=0o700)
    created: list[Path] = []
    for target in targets:
        base = f"{_safe_name(target.pod)}-{_safe_name(target.container)}"
        if target.kind == "postgresql":
            path = backup_dir / f"{base}-postgresql.sql.gz"
            remote = (
                "sh",
                "-ceu",
                'export PGPASSWORD="${POSTGRES_PASSWORD:-}"; '
                'exec pg_dumpall --clean --if-exists -U "${POSTGRES_USER:-postgres}"',
            )
            runner.stream_to_gzip(
                cluster.exec_arguments(target.pod, target.container, remote),
                path,
                timeout=1200,
            )
            _validate_gzip_contains(
                path, (b"PostgreSQL database cluster dump", b"CREATE DATABASE")
            )
        elif target.kind == "mysql":
            path = backup_dir / f"{base}-mysql.sql.gz"
            remote = (
                "sh",
                "-ceu",
                'if [ -n "${MYSQL_ROOT_PASSWORD:-}" ]; then user=root; '
                'export MYSQL_PWD="$MYSQL_ROOT_PASSWORD"; else '
                'user="${MYSQL_USER:-root}"; export MYSQL_PWD="${MYSQL_PASSWORD:-}"; fi; '
                'exec mysqldump --all-databases --single-transaction --routines '
                '--events --triggers -u"$user"',
            )
            runner.stream_to_gzip(
                cluster.exec_arguments(target.pod, target.container, remote),
                path,
                timeout=1200,
            )
            _validate_gzip_contains(path, (b"MySQL dump", b"MariaDB dump"))
        elif target.kind == "redis":
            path = backup_dir / f"{base}-redis.rdb"
            remote = (
                "sh",
                "-ceu",
                'if [ -n "${REDIS_PASSWORD:-}" ]; then '
                'redis-cli --no-auth-warning -a "$REDIS_PASSWORD" SAVE >/dev/null; '
                'dir="$(redis-cli --no-auth-warning -a "$REDIS_PASSWORD" --raw CONFIG GET dir | tail -n 1)"; '
                'file="$(redis-cli --no-auth-warning -a "$REDIS_PASSWORD" --raw CONFIG GET dbfilename | tail -n 1)"; '
                "else redis-cli SAVE >/dev/null; "
                'dir="$(redis-cli --raw CONFIG GET dir | tail -n 1)"; '
                'file="$(redis-cli --raw CONFIG GET dbfilename | tail -n 1)"; fi; '
                'test -n "$dir"; test -n "$file"; '
                'command -v redis-check-rdb >/dev/null; '
                'redis-check-rdb "$dir/$file" >/dev/null; exec cat "$dir/$file"',
            )
            runner.stream_raw(
                cluster.exec_arguments(target.pod, target.container, remote),
                path,
                timeout=600,
            )
            _validate_redis_rdb(path)
        else:
            raise RetirementError("An unsupported data-container type was selected.")
        created.append(path)
    return created


def _tar_host_directory(
    runner: CommandRunner, source: Path, destination: Path
) -> None:
    runner.stream_raw(
        ("tar", "-C", str(source), "-czf", "-", "."),
        destination,
        timeout=1800,
    )
    _validate_archive(destination)


def _archive_quiesced_host_paths(
    runner: CommandRunner,
    sources: Sequence[PvcSource],
    destination: Path,
    *,
    home: Path,
    backup_root: Path,
) -> list[Path]:
    backup_dir = destination / "hostpath-quiesced"
    backup_dir.mkdir(mode=0o700)
    created: list[Path] = []
    for source in sources:
        if source.host_path is None:
            continue
        expanded = source.host_path.expanduser()
        if not _is_external_host_path(expanded):
            continue
        if not expanded.exists():
            raise RetirementError(
                f"External hostPath PV {source.pv} disappeared before backup."
            )
        host_path = _safe_host_data_path(expanded, home=home, backup_root=backup_root)
        path = backup_dir / f"{_safe_name(source.pv)}.tar.gz"
        _tar_host_directory(runner, host_path, path)
        created.append(path)
    return created


def _suspend_cronjobs(cluster: KubernetesRuntime, state: Mapping[str, Any]) -> None:
    for name in sorted(state["cronjobs"]):
        cluster.patch("cronjob.batch", name, {"spec": {"suspend": True}})


def _active_user_jobs(cluster: KubernetesRuntime) -> list[dict[str, str]]:
    jobs = cluster.list_resource("jobs.batch", all_namespaces=True)["items"]
    return sorted(
        (
            {
                "kind": "Job",
                "namespace": str(item.get("metadata", {}).get("namespace")),
                "name": str(item.get("metadata", {}).get("name")),
            }
            for item in jobs
            if item.get("metadata", {}).get("namespace") not in SYSTEM_NAMESPACES
            and int(item.get("status", {}).get("active", 0) or 0) > 0
        ),
        key=lambda item: (item["namespace"], item["name"]),
    )


def _require_no_active_jobs_after_cron_suspend(cluster: KubernetesRuntime) -> None:
    if _active_user_jobs(cluster):
        raise RetirementError(
            "A Kubernetes Job became active while CronJobs were being suspended."
        )


def _scale_writers(cluster: KubernetesRuntime, state: Mapping[str, Any]) -> None:
    for name in sorted(state["writerDeployments"]):
        cluster.patch("deployment.apps", name, {"spec": {"replicas": 0}})
    for workload in state["auxiliaryDeployments"]:
        cluster.patch(
            "deployment.apps",
            workload["name"],
            {"spec": {"replicas": 0}},
            namespace=workload["namespace"],
        )
    for name in sorted(state["writerStatefulsets"]):
        cluster.patch("statefulset.apps", name, {"spec": {"replicas": 0}})


def _scale_data_workloads(cluster: KubernetesRuntime, state: Mapping[str, Any]) -> None:
    for name in sorted(state["dataDeployments"]):
        cluster.patch("deployment.apps", name, {"spec": {"replicas": 0}})
    for name in sorted(state["dataStatefulsets"]):
        cluster.patch("statefulset.apps", name, {"spec": {"replicas": 0}})


def _wait_for_group_scaled_down(
    cluster: KubernetesRuntime,
    deployments: Mapping[str, int],
    statefulsets: Mapping[str, int],
    pods: Sequence[Mapping[str, str]],
    *,
    auxiliary_deployments: Sequence[Mapping[str, Any]] = (),
    timeout: int = 600,
) -> None:
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        deployment_items = cluster.list_resource("deployments.apps")["items"]
        statefulset_items = cluster.list_resource("statefulsets.apps")["items"]
        deployment_by_name = {
            item.get("metadata", {}).get("name"): item for item in deployment_items
        }
        statefulset_by_name = {
            item.get("metadata", {}).get("name"): item for item in statefulset_items
        }
        all_zero = all(
            name in deployment_by_name
            and int(deployment_by_name[name].get("status", {}).get("replicas", 0) or 0)
            == 0
            and int(
                deployment_by_name[name].get("status", {}).get("readyReplicas", 0)
                or 0
            )
            == 0
            for name in deployments
        ) and all(
            name in statefulset_by_name
            and int(statefulset_by_name[name].get("status", {}).get("replicas", 0) or 0)
            == 0
            and int(
                statefulset_by_name[name].get("status", {}).get("readyReplicas", 0)
                or 0
            )
            == 0
            for name in statefulsets
        )
        for workload in auxiliary_deployments:
            auxiliary = cluster.list_resource(
                "deployments.apps", namespace=workload["namespace"]
            )["items"]
            match = next(
                (
                    item
                    for item in auxiliary
                    if item.get("metadata", {}).get("name") == workload["name"]
                ),
                None,
            )
            all_zero = all_zero and match is not None and all(
                int(match.get("status", {}).get(field, 0) or 0) == 0
                for field in ("replicas", "readyReplicas")
            )
        pods_gone = all(
            not cluster.pod_exists(
                pod["name"], namespace=pod.get("namespace", NAMESPACE)
            )
            for pod in pods
        )
        if all_zero and pods_gone:
            return
        time.sleep(5)
    raise RetirementError("BuddyStudy Kubernetes workloads did not finish stopping.")


def _wait_for_all_scaled_down(
    cluster: KubernetesRuntime, state: Mapping[str, Any], *, timeout: int = 600
) -> None:
    _wait_for_group_scaled_down(
        cluster,
        {**state["writerDeployments"], **state["dataDeployments"]},
        {**state["writerStatefulsets"], **state["dataStatefulsets"]},
        [*state["writerPods"], *state["dataPods"]],
        auxiliary_deployments=state["auxiliaryDeployments"],
        timeout=timeout,
    )


def _wait_for_writers_scaled_down(
    cluster: KubernetesRuntime, state: Mapping[str, Any], *, timeout: int = 600
) -> None:
    _wait_for_group_scaled_down(
        cluster,
        state["writerDeployments"],
        state["writerStatefulsets"],
        state["writerPods"],
        auxiliary_deployments=state["auxiliaryDeployments"],
        timeout=timeout,
    )


def _restore_cluster_state(cluster: KubernetesRuntime, state: Mapping[str, Any]) -> None:
    failures = 0
    deployments = {
        **state.get("writerDeployments", {}),
        **state.get("dataDeployments", {}),
    }
    for name, replicas in sorted(deployments.items()):
        try:
            cluster.patch("deployment.apps", name, {"spec": {"replicas": replicas}})
        except RetirementError:
            failures += 1
    statefulsets = {
        **state.get("writerStatefulsets", {}),
        **state.get("dataStatefulsets", {}),
    }
    for name, replicas in sorted(statefulsets.items()):
        try:
            cluster.patch("statefulset.apps", name, {"spec": {"replicas": replicas}})
        except RetirementError:
            failures += 1
    for name, suspended in sorted(state.get("cronjobs", {}).items()):
        try:
            cluster.patch("cronjob.batch", name, {"spec": {"suspend": suspended}})
        except RetirementError:
            failures += 1
    for workload in state.get("auxiliaryDeployments", []):
        try:
            cluster.patch(
                "deployment.apps",
                workload["name"],
                {"spec": {"replicas": workload["replicas"]}},
                namespace=workload["namespace"],
            )
        except RetirementError:
            failures += 1
    if failures:
        raise RetirementError("One or more Kubernetes workload states could not be restored.")


def _is_kubernetes_container(name: str, labels: str) -> bool:
    return name.startswith("k8s_") or "io.kubernetes." in labels


class DockerDesktopRuntime:
    PROCESS_PATTERN = (
        r"/Docker\.app/Contents/MacOS/(Docker|com\.docker\.backend|"
        r"com\.docker\.virtualization)"
    )

    def __init__(self, runner: CommandRunner) -> None:
        self.runner = runner

    def ensure_ready(self, *, timeout: int = 240) -> None:
        context = self.runner.run(
            ("docker", "context", "show"), timeout=15, check=False
        )
        if (
            context.returncode != 0
            or context.stdout.decode("utf-8", errors="replace").strip()
            != "desktop-linux"
        ):
            raise RetirementError(
                "Docker CLI does not target the exact desktop-linux context."
            )
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            if self.runner.run(("docker", "info"), timeout=15, check=False).returncode == 0:
                return
            time.sleep(5)
        raise RetirementError("Docker Desktop did not become ready.")

    def _rows(self, *, running_only: bool) -> list[tuple[str, str, str]]:
        arguments = ["docker", "ps"]
        if not running_only:
            arguments.append("--all")
        arguments.extend(("--format", "{{.ID}}\t{{.Names}}\t{{.Labels}}"))
        result = self.runner.run(arguments, timeout=60)
        rows = []
        for line in result.stdout.decode("utf-8", errors="replace").splitlines():
            parts = line.split("\t", 2)
            if len(parts) != 3:
                raise RetirementError("Docker returned invalid container inventory.")
            rows.append((parts[0], parts[1], parts[2]))
        return rows

    def inventory(self) -> dict[str, Any]:
        rows = self._rows(running_only=False)
        non_kubernetes = {
            name: container_id
            for container_id, name, labels in rows
            if not _is_kubernetes_container(name, labels)
        }
        kubernetes_total = sum(
            1 for _, name, labels in rows if _is_kubernetes_container(name, labels)
        )
        kubernetes_running = sum(
            1
            for _, name, labels in self._rows(running_only=True)
            if _is_kubernetes_container(name, labels)
        )
        volume_result = self.runner.run(
            ("docker", "volume", "ls", "--format", "{{.Name}}"), timeout=60
        )
        volumes = sorted(
            line
            for line in volume_result.stdout.decode(
                "utf-8", errors="replace"
            ).splitlines()
            if line
        )
        network_result = self.runner.run(
            ("docker", "network", "ls", "--format", "{{.ID}}\t{{.Name}}"),
            timeout=60,
        )
        networks: dict[str, str] = {}
        for line in network_result.stdout.decode(
            "utf-8", errors="replace"
        ).splitlines():
            parts = line.split("\t", 1)
            if len(parts) != 2:
                raise RetirementError("Docker returned invalid network inventory.")
            networks[parts[1]] = parts[0]
        return {
            "nonKubernetes": non_kubernetes,
            "volumes": volumes,
            "networks": networks,
            "kubernetesTotal": kubernetes_total,
            "kubernetesRunning": kubernetes_running,
        }

    def _processes_running(self) -> bool:
        result = self.runner.run(
            ("pgrep", "-f", self.PROCESS_PATTERN), timeout=15, check=False
        )
        return result.returncode == 0

    def _wait_stopped(self, timeout: int) -> bool:
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            if not self._processes_running():
                return True
            time.sleep(3)
        return not self._processes_running()

    def stop(self) -> str:
        help_result = self.runner.run(
            ("docker", "desktop", "--help"), timeout=30, check=False
        )
        help_text = help_result.stdout.decode("utf-8", errors="replace")
        method = "application-quit"
        if help_result.returncode == 0 and re.search(r"(?m)^\s+stop\s", help_text):
            self.runner.run(
                ("docker", "desktop", "stop", "--timeout", "120"),
                timeout=150,
                check=False,
            )
            method = "desktop-cli"
        else:
            self.runner.run(
                ("osascript", "-e", 'tell application "Docker" to quit'),
                timeout=30,
                check=False,
            )
        if self._wait_stopped(120):
            return method
        raise RetirementError(
            "Docker Desktop did not stop gracefully; forced termination is intentionally disabled."
        )

    def stop_if_running(self) -> str:
        if not self._processes_running():
            return "already-stopped"
        return self.stop()

    def start(self) -> None:
        result = self.runner.run(("open", "-gja", "Docker"), timeout=30, check=False)
        if result.returncode != 0:
            raise RetirementError("Docker Desktop could not be started.")
        self.ensure_ready()

    def wait_for_no_running_kubernetes(self, *, timeout: int = 300) -> dict[str, Any]:
        deadline = time.monotonic() + timeout
        latest = self.inventory()
        while time.monotonic() < deadline:
            latest = self.inventory()
            if latest["kubernetesRunning"] == 0:
                return latest
            time.sleep(5)
        raise RetirementError("Kubernetes-labeled control-plane containers are still running.")


def _verify_expected_report(path: Path, digest: str, expected_digest: str) -> None:
    if not re.fullmatch(r"[0-9a-f]{64}", expected_digest):
        raise RetirementError("Apply requires the exact 64-character preflight digest.")
    report = _read_json(path, "preflight report")
    if not isinstance(report, Mapping) or report.get("mode") != "preflight":
        raise RetirementError("The expected preflight report is invalid.")
    if report.get("inventoryDigest") != digest or expected_digest != digest:
        raise RetirementError("Kubernetes desired state changed after preflight.")


def _revalidate_before_mutation(
    cluster: KubernetesRuntime,
    desktop: DockerDesktopRuntime,
    *,
    home: Path,
    expected_report: Path,
    expected_digest: str,
    settings: SettingsTarget,
    settings_original: bytes,
    docker_raw_source: Path,
    baseline: Mapping[str, Any],
) -> dict[str, Any]:
    inventory = collect_inventory(cluster)
    _verify_expected_report(expected_report, inventory["digest"], expected_digest)
    current_settings = discover_settings(home)
    if current_settings != settings or settings.path.read_bytes() != settings_original:
        raise RetirementError("Docker Desktop settings changed before mutation.")
    if _docker_raw_path(current_settings, home) != docker_raw_source:
        raise RetirementError("Docker.raw identity changed before mutation.")
    current_docker = desktop.inventory()
    for identity_key in ("nonKubernetes", "volumes", "networks"):
        if current_docker[identity_key] != baseline[identity_key]:
            raise RetirementError("Docker identity changed before mutation.")
    report = build_preflight_report(inventory, current_settings, current_docker)
    if not report["ready"]:
        raise RetirementError("Retirement became unsafe immediately before mutation.")
    return inventory


def build_preflight_report(
    inventory: Mapping[str, Any], settings: SettingsTarget, docker: Mapping[str, Any]
) -> dict[str, Any]:
    sources = pvc_sources(inventory)
    databases = database_targets(inventory["pods"])
    stopped_databases = stopped_database_containers(inventory["pods"])
    workload_counts = {
        resource: len(inventory["resources"][resource]["items"])
        for resource in WORKLOAD_RESOURCES
    }
    blockers = []
    if not settings.enabled:
        blockers.append("kubernetes-setting-disabled")
    if docker["kubernetesRunning"] == 0:
        blockers.append("no-running-kubernetes-containers")
    if inventory["foreignUserWorkloads"]:
        blockers.append("foreign-user-workloads")
    if inventory["unsupportedTargetWorkloads"]:
        blockers.append("unsupported-target-workloads")
    if inventory["activeJobs"]:
        blockers.append("active-jobs")
    if inventory["standalonePods"]:
        blockers.append("standalone-pods")
    if inventory["unknownReplicaSets"]:
        blockers.append("unknown-replicasets")
    if stopped_databases:
        blockers.append("data-container-not-running")

    workload_identities = []
    for resource in WORKLOAD_RESOURCES:
        for item in inventory["resources"][resource]["items"]:
            workload_identities.append(
                {
                    "namespace": NAMESPACE,
                    "kind": item.get("kind"),
                    "name": item.get("metadata", {}).get("name"),
                }
            )
    for item in inventory["auxiliaryWorkloads"]:
        workload_identities.append(
            {
                "namespace": item.get("metadata", {}).get("namespace"),
                "kind": item.get("kind"),
                "name": item.get("metadata", {}).get("name"),
            }
        )

    return {
        "mode": "preflight",
        "ready": not blockers,
        "blockers": blockers,
        "context": KUBE_CONTEXT,
        "namespace": NAMESPACE,
        "inventoryDigest": inventory["digest"],
        "settingsFile": settings.path.name,
        "settingsKey": settings.key,
        "kubernetesEnabled": settings.enabled,
        "workloadCounts": workload_counts,
        "pvcCount": len(sources),
        "hostPathPvCount": sum(source.host_path is not None for source in sources),
        "databaseCounts": {
            kind: sum(target.kind == kind for target in databases)
            for kind in ("postgresql", "mysql", "redis")
        },
        "workloadIdentities": sorted(
            workload_identities,
            key=lambda item: (str(item["namespace"]), str(item["kind"]), str(item["name"])),
        ),
        "pvcIdentities": sorted(
            ({"name": source.claim, "pv": source.pv} for source in sources),
            key=lambda item: item["name"],
        ),
        "databaseIdentities": sorted(
            (
                {"kind": target.kind, "pod": target.pod, "container": target.container}
                for target in databases
            ),
            key=lambda item: (item["kind"], item["pod"], item["container"]),
        ),
        "blockingWorkloadIdentities": sorted(
            (
                *inventory["foreignUserWorkloads"],
                *inventory["unsupportedTargetWorkloads"],
                *inventory["activeJobs"],
                *inventory["standalonePods"],
                *inventory["unknownReplicaSets"],
            ),
            key=lambda item: (
                str(item.get("namespace")),
                str(item.get("kind")),
                str(item.get("name")),
            ),
        ),
        "foreignUserWorkloadCount": len(inventory["foreignUserWorkloads"]),
        "unsupportedTargetWorkloadCount": len(
            inventory["unsupportedTargetWorkloads"]
        ),
        "activeJobCount": len(inventory["activeJobs"]),
        "standalonePodCount": len(inventory["standalonePods"]),
        "unknownReplicaSetCount": len(inventory["unknownReplicaSets"]),
        "stoppedDataContainerCount": len(stopped_databases),
        "kubernetesContainerCount": docker["kubernetesTotal"],
        "runningKubernetesContainerCount": docker["kubernetesRunning"],
        "nonKubernetesContainerCount": len(docker["nonKubernetes"]),
        "dockerVolumeCount": len(docker["volumes"]),
        "dockerNetworkCount": len(docker["networks"]),
    }


def preflight(
    cluster: KubernetesRuntime,
    desktop: DockerDesktopRuntime,
    *,
    home: Path,
) -> dict[str, Any]:
    desktop.ensure_ready()
    inventory = collect_inventory(cluster)
    settings = discover_settings(home)
    docker_raw = _docker_raw_path(settings, home)
    _require_filevault(desktop.runner)
    sources = pvc_sources(inventory)
    external_paths = _validate_external_host_paths(
        sources,
        home=home,
        backup_root=home / DEFAULT_BACKUP_RELATIVE_PATH,
    )
    capacity = _validate_backup_preconditions(
        desktop.runner,
        docker_raw,
        home / DEFAULT_BACKUP_RELATIVE_PATH,
        home=home,
        external_paths=external_paths,
    )
    docker = desktop.inventory()
    report = build_preflight_report(inventory, settings, docker)
    report["backupCapacity"] = capacity
    return report


def _write_checksum_manifest(destination: Path) -> None:
    entries = []
    for path in sorted(destination.rglob("*")):
        if path.is_file() and path.name != "sha256.json":
            entries.append(
                {
                    "path": str(path.relative_to(destination)),
                    "bytes": path.stat().st_size,
                    "sha256": _sha256(path),
                }
            )
    _write_json(destination / "sha256.json", {"files": entries})


def retire(
    cluster: KubernetesRuntime,
    desktop: DockerDesktopRuntime,
    runner: CommandRunner,
    *,
    home: Path,
    backup_root: Path,
    run_key: str,
    confirmation: str,
    expected_report: Path,
    expected_digest: str,
    encryption_secret: str,
) -> dict[str, Any]:
    if confirmation != CONFIRMATION:
        raise RetirementError("The exact Kubernetes retirement confirmation is required.")

    desktop.ensure_ready()
    inventory = collect_inventory(cluster)
    _verify_expected_report(expected_report, inventory["digest"], expected_digest)
    settings = discover_settings(home)
    docker_raw_source = _docker_raw_path(settings, home)
    _require_filevault(runner)
    sources = pvc_sources(inventory)
    external_paths = _validate_external_host_paths(
        sources, home=home, backup_root=backup_root
    )
    _validate_backup_preconditions(
        runner,
        docker_raw_source,
        backup_root,
        home=home,
        external_paths=external_paths,
    )
    baseline = desktop.inventory()
    report = build_preflight_report(inventory, settings, baseline)
    if not report["ready"]:
        raise RetirementError(
            "Retirement preflight is not ready; Kubernetes must be enabled and no foreign user workload may exist."
        )
    _ensure_keychain_recovery_key(runner, encryption_secret)

    destination = _prepare_backup_directory(home, backup_root, run_key)
    if docker_raw_source.stat().st_dev != destination.stat().st_dev:
        raise RetirementError(
            "The retirement backup root is not on Docker.raw's APFS filesystem."
        )
    state = workload_state(inventory)
    settings_original = settings.path.read_bytes()
    settings_original_mode = settings.path.stat().st_mode & 0o777
    payload = destination / "private-staging"

    cluster_changed = False
    settings_may_have_changed = False
    docker_may_need_start = False
    raw_clone: dict[str, Any] | None = None
    stop_method = "not-stopped"
    rollback_failures: list[str] = []
    try:
        payload = _create_private_staging(
            destination,
            settings_original=settings_original,
            inventory=inventory,
            state=state,
            settings=settings,
            baseline=baseline,
        )
        mutation_inventory = _revalidate_before_mutation(
            cluster,
            desktop,
            home=home,
            expected_report=expected_report,
            expected_digest=expected_digest,
            settings=settings,
            settings_original=settings_original,
            docker_raw_source=docker_raw_source,
            baseline=baseline,
        )
        state = workload_state(mutation_inventory)
        databases = database_targets(mutation_inventory["pods"])
        sources = pvc_sources(mutation_inventory)
        cluster_changed = True
        _suspend_cronjobs(cluster, state)
        _require_no_active_jobs_after_cron_suspend(cluster)
        _scale_writers(cluster, state)
        _wait_for_writers_scaled_down(cluster, state)

        logical = _backup_databases(cluster, runner, databases, payload)

        _scale_data_workloads(cluster, state)
        _wait_for_all_scaled_down(cluster, state)
        quiesced = _archive_quiesced_host_paths(
            runner,
            sources,
            payload,
            home=home,
            backup_root=backup_root,
        )
        _write_checksum_manifest(payload)
        _seal_payload(
            payload,
            destination / "retirement-backup.tar.enc",
            secret=encryption_secret,
        )

        docker_may_need_start = True
        stop_method = desktop.stop()
        raw_clone = _clone_docker_raw(
            runner,
            docker_raw_source,
            destination / "Docker.raw.apfs-clone",
            secret=encryption_secret,
        )
        settings_may_have_changed = True
        atomically_set_kubernetes_enabled(settings, False)
        desktop.start()
        docker_may_need_start = False

        updated_settings = discover_settings(home)
        if updated_settings.path != settings.path or updated_settings.key != settings.key:
            raise RetirementError("Docker Desktop settings identity changed after restart.")
        if updated_settings.enabled:
            raise RetirementError("Docker Desktop Kubernetes remains enabled after restart.")
        final_inventory = desktop.wait_for_no_running_kubernetes()
        if final_inventory["nonKubernetes"] != baseline["nonKubernetes"]:
            raise RetirementError("A non-Kubernetes Docker container identity changed.")
        if final_inventory["volumes"] != baseline["volumes"]:
            raise RetirementError("A Docker volume identity changed.")
        if final_inventory["networks"] != baseline["networks"]:
            raise RetirementError("A Docker network identity changed.")

        completed = {
            "completed": True,
            "settingsDisabled": True,
            "stopMethod": stop_method,
            "logicalBackupCount": len(logical),
            "pvcSnapshotCount": len(sources),
            "pvcSnapshotStrategy": "verified-apfs-docker-raw-clone",
            "quiescedHostPathArchiveCount": len(quiesced),
            "dockerRawCloneBytes": raw_clone["bytes"],
            "baselineKubernetesContainerCount": baseline["kubernetesTotal"],
            "finalKubernetesContainerCount": final_inventory["kubernetesTotal"],
            "finalRunningKubernetesContainerCount": final_inventory[
                "kubernetesRunning"
            ],
            "preservedNonKubernetesContainerCount": len(
                final_inventory["nonKubernetes"]
            ),
            "preservedDockerVolumeCount": len(final_inventory["volumes"]),
            "preservedDockerNetworkCount": len(final_inventory["networks"]),
        }
        _write_json(destination / "completed.json", completed)
        return {
            "mode": "retire",
            "status": "retired",
            "context": KUBE_CONTEXT,
            "namespace": NAMESPACE,
            "backupDirectory": str(destination),
            **completed,
        }
    except Exception as original_error:
        _protect_rollback_from_additional_signals()
        if payload.exists():
            try:
                _write_checksum_manifest(payload)
                _seal_payload(
                    payload,
                    destination / "failed-retirement-backup.tar.enc",
                    secret=encryption_secret,
                )
            except Exception:
                try:
                    _remove_private_staging(payload, destination)
                except Exception:
                    rollback_failures.append("private-staging-cleanup")

        if settings_may_have_changed:
            stopped_for_rollback = False
            raw_restored = False
            try:
                desktop.stop_if_running()
                docker_may_need_start = True
                stopped_for_rollback = True
            except Exception:
                rollback_failures.append("docker-stop")
            if stopped_for_rollback and raw_clone is not None:
                try:
                    _restore_docker_raw_clone(
                        runner,
                        source=docker_raw_source,
                        clone=destination / "Docker.raw.apfs-clone",
                        expected_hmac=raw_clone["hmacSha256"],
                        destination=destination,
                        secret=encryption_secret,
                    )
                    raw_restored = True
                except Exception:
                    rollback_failures.append("docker-raw")
            if stopped_for_rollback and raw_clone is None:
                rollback_failures.append("docker-raw-metadata")
            if stopped_for_rollback and raw_restored:
                try:
                    atomically_restore_bytes(
                        settings_original, settings_original_mode, settings.path
                    )
                except Exception:
                    rollback_failures.append("docker-settings")
                try:
                    desktop.start()
                    docker_may_need_start = False
                except Exception:
                    rollback_failures.append("docker-start")
        elif docker_may_need_start:
            try:
                desktop.start()
                docker_may_need_start = False
            except Exception:
                rollback_failures.append("docker-start")

        if cluster_changed:
            try:
                deadline = time.monotonic() + 300
                while time.monotonic() < deadline:
                    try:
                        cluster.ensure_exact_target()
                        break
                    except RetirementError:
                        time.sleep(5)
                else:
                    raise RetirementError("Kubernetes did not return for rollback.")
                _restore_cluster_state(cluster, state)
            except Exception:
                rollback_failures.append("workload-state")

        if rollback_failures:
            raise RetirementError(
                "Retirement failed and best-effort rollback was incomplete: "
                + ", ".join(rollback_failures)
                + f". Host backup remains at {destination}."
            ) from original_error
        if isinstance(original_error, RetirementError):
            raise
        raise RetirementError(
            f"Retirement failed safely; the host backup remains at {destination}."
        ) from original_error


def _render_summary(report: Mapping[str, Any], status: str) -> str:
    lines = ["## Docker Desktop Kubernetes retirement", ""]
    if report.get("mode") == "preflight":
        workload_plan = ", ".join(
            f"{item.get('namespace')}/{item.get('kind')}/{item.get('name')}"
            for item in report.get("workloadIdentities", [])
        ) or "none"
        pvc_plan = ", ".join(
            f"{item.get('name')}→{item.get('pv')}"
            for item in report.get("pvcIdentities", [])
        ) or "none"
        blocking_plan = ", ".join(
            f"{item.get('namespace')}/{item.get('kind')}/{item.get('name')}"
            for item in report.get("blockingWorkloadIdentities", [])
        ) or "none"
        lines.extend(
            (
                f"- Workflow status: `{status}`",
                "- Mode: read-only preflight",
                f"- Exact target: `{report.get('context')}` / `{report.get('namespace')}`",
                f"- Ready: `{str(bool(report.get('ready'))).lower()}`",
                f"- Blockers: `{', '.join(report.get('blockers', [])) or 'none'}`",
                f"- Desired-state digest: `{report.get('inventoryDigest', 'unavailable')}`",
                f"- Exact workload plan: `{workload_plan}`",
                f"- Exact PVC plan: `{pvc_plan}`",
                f"- Blocking workload identities: `{blocking_plan}`",
                f"- PVCs: `{report.get('pvcCount', 0)}`",
                f"- Running data containers: `{sum((report.get('databaseCounts') or {}).values())}`",
                f"- Foreign user workloads: `{report.get('foreignUserWorkloadCount', 0)}`",
                f"- Kubernetes containers: `{report.get('kubernetesContainerCount', 0)}` total / `{report.get('runningKubernetesContainerCount', 0)}` running",
                "- No Kubernetes or Docker runtime state was changed.",
            )
        )
    elif report.get("mode") == "retire":
        lines.extend(
            (
                f"- Workflow status: `{status}`",
                f"- Retirement status: `{report.get('status', 'unknown')}`",
                f"- Exact target: `{report.get('context')}` / `{report.get('namespace')}`",
                f"- Host-only backup: `{report.get('backupDirectory', 'unavailable')}`",
                f"- Logical dumps: `{report.get('logicalBackupCount', 0)}`",
                f"- PVCs covered by rollback snapshot: `{report.get('pvcSnapshotCount', 0)}`",
                f"- PVC backup strategy: `{report.get('pvcSnapshotStrategy', 'unavailable')}`",
                f"- Quiesced hostPath archives: `{report.get('quiescedHostPathArchiveCount', 0)}`",
                f"- Kubernetes containers: `{report.get('baselineKubernetesContainerCount', 0)}` before / `{report.get('finalKubernetesContainerCount', 0)}` after / `{report.get('finalRunningKubernetesContainerCount', 0)}` running",
                f"- Non-Kubernetes container identities preserved: `{report.get('preservedNonKubernetesContainerCount', 0)}`",
                f"- Docker volume identities preserved: `{report.get('preservedDockerVolumeCount', 0)}`",
                f"- Docker network identities preserved: `{report.get('preservedDockerNetworkCount', 0)}`",
                "- No backup was uploaded as an Actions artifact and no secret payload was printed.",
                "- No namespace, PVC, PV, Docker volume, container, or network was removed or pruned.",
            )
        )
    else:
        lines.extend(
            (
                f"- Workflow status: `{status}`",
                "- Report: unavailable; inspect the secret-free failure message.",
            )
        )
    return "\n".join(lines) + "\n"


def _load_report_if_present(path: Path) -> dict[str, Any]:
    if not path.is_file():
        return {}
    value = _read_json(path, "retirement report")
    return value if isinstance(value, dict) else {}


def _ensure_host() -> None:
    if platform.system() != "Darwin" or platform.machine().lower() not in (
        "arm64",
        "aarch64",
    ):
        raise RetirementError("This helper runs only on the MacBook Air ARM64 runner.")
    if os.environ.get("GITHUB_ACTIONS") != "true":
        raise RetirementError("This helper runs only inside the audited GitHub Actions job.")
    if os.environ.get("RUNNER_NAME") != "macbook-air-buddystudy":
        raise RetirementError("This helper requires the exact MacBook Air runner identity.")


def main(argv: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser()
    subparsers = parser.add_subparsers(dest="command", required=True)

    preflight_parser = subparsers.add_parser("preflight")
    preflight_parser.add_argument("--report", required=True, type=Path)

    retire_parser = subparsers.add_parser("retire")
    retire_parser.add_argument("--report", required=True, type=Path)
    retire_parser.add_argument("--expected-report", required=True, type=Path)
    retire_parser.add_argument("--expected-digest", required=True)
    retire_parser.add_argument("--confirmation", required=True)
    retire_parser.add_argument("--run-key", required=True)
    retire_parser.add_argument("--backup-root", type=Path)

    summary_parser = subparsers.add_parser("render-summary")
    summary_parser.add_argument("--report", required=True, type=Path)
    summary_parser.add_argument("--status", required=True)

    arguments = parser.parse_args(argv)
    if arguments.command == "render-summary":
        sys.stdout.write(_render_summary(_load_report_if_present(arguments.report), arguments.status))
        return 0

    _ensure_host()
    home = Path.home().resolve()
    runner = CommandRunner()
    cluster = KubernetesRuntime(runner)
    desktop = DockerDesktopRuntime(runner)
    try:
        if arguments.command == "preflight":
            report = preflight(cluster, desktop, home=home)
        else:
            backup_root = arguments.backup_root or home / DEFAULT_BACKUP_RELATIVE_PATH
            encryption_secret = _backup_secret_from_environment(
                "MACBOOKAIR_K8S_RETIREMENT_BACKUP_KEY"
            )
            os.environ.pop("MACBOOKAIR_K8S_RETIREMENT_BACKUP_KEY", None)
            _install_termination_guard()
            report = retire(
                cluster,
                desktop,
                runner,
                home=home,
                backup_root=backup_root,
                run_key=arguments.run_key,
                confirmation=arguments.confirmation,
                expected_report=arguments.expected_report,
                expected_digest=arguments.expected_digest,
                encryption_secret=encryption_secret,
            )
        _write_json(arguments.report, report)
        return 0
    except RetirementError as error:
        _write_json(
            arguments.report,
            {"mode": arguments.command, "status": "failed", "error": str(error)},
        )
        print(f"Kubernetes retirement failed: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
