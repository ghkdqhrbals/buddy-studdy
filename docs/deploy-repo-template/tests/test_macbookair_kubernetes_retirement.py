import copy
import importlib.util
import io
import json
import os
import subprocess
import sys
import tempfile
import time
import unittest
from pathlib import Path
from unittest import mock


ROOT = Path(__file__).resolve().parents[1]
HELPER = ROOT / "scripts" / "retire_macbookair_kubernetes.py"
WORKFLOW = ROOT / "retire-macbookair-kubernetes.yml"

spec = importlib.util.spec_from_file_location("kubernetes_retirement", HELPER)
retirement = importlib.util.module_from_spec(spec)
assert spec.loader is not None
sys.modules[spec.name] = retirement
spec.loader.exec_module(retirement)


def item(kind, name, *, namespace="buddystudy", spec_value=None, rv="1"):
    return {
        "apiVersion": "apps/v1" if kind in ("Deployment", "StatefulSet") else "v1",
        "kind": kind,
        "metadata": {
            "name": name,
            "namespace": namespace,
            "uid": f"uid-{name}",
            "resourceVersion": rv,
            "generation": 1,
        },
        "spec": spec_value or {},
        "status": {"readyReplicas": 1},
    }


def empty_inventory():
    resources = {
        resource: {"items": []} for resource in retirement.NAMESPACED_RESOURCES
    }
    namespace = item("Namespace", "buddystudy", namespace=None)
    inventory = {
        "resources": resources,
        "namespaceManifest": namespace,
        "pvs": [],
        "pods": {"items": []},
        "auxiliaryPods": {"items": []},
        "auxiliaryWorkloads": [],
        "foreignUserWorkloads": [],
        "activeJobs": [],
        "standalonePods": [],
        "unsupportedTargetWorkloads": [],
        "unknownReplicaSets": [],
    }
    inventory["digest"] = retirement.inventory_digest(namespace, resources, [], [])
    return inventory


class KubernetesRetirementSafetyTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.workflow = WORKFLOW.read_text(encoding="utf-8")
        cls.helper = HELPER.read_text(encoding="utf-8")

    def test_workflow_is_two_run_manual_only(self):
        self.assertIn("workflow_dispatch:", self.workflow)
        self.assertNotIn("schedule:", self.workflow)
        self.assertNotIn("push:", self.workflow)
        self.assertIn("default: false", self.workflow)
        self.assertIn("expected_inventory_digest", self.workflow)
        self.assertIn("^[0-9a-f]{64}$", self.workflow)
        self.assertIn(retirement.CONFIRMATION, self.workflow)
        self.assertIn("MACBOOKAIR_K8S_RETIREMENT_BACKUP_KEY", self.workflow)
        self.assertIn("timeout-minutes: 360", self.workflow)
        self.assertLess(
            self.workflow.index("Read-only preflight"),
            self.workflow.index("Back up and retire"),
        )

    def test_workflow_targets_only_the_exact_air_runner(self):
        self.assertIn(
            "runs-on: [self-hosted, macOS, ARM64, macbook-air, buddystudy]",
            self.workflow,
        )
        self.assertIn('RUNNER_NAME") != "macbook-air-buddystudy"', self.helper)
        self.assertIn('os.environ.get("GITHUB_ACTIONS") != "true"', self.helper)

    def test_no_destructive_cluster_or_docker_cleanup_commands(self):
        combined = self.workflow + "\n" + self.helper
        forbidden = (
            "kubectl delete",
            "docker rm",
            "docker container rm",
            "docker volume rm",
            "docker system prune",
            "docker volume prune",
            "docker network prune",
            "factory-reset",
            "reset Kubernetes cluster",
            "rm -v",
            "upload-artifact",
            "pkill",
            "kill -KILL",
        )
        for value in forbidden:
            with self.subTest(value=value):
                self.assertNotIn(value, combined)

    def test_no_runtime_http_or_database_health_gate(self):
        for value in (
            "curl ",
            "wget ",
            "/health",
            "mysqladmin ping",
            "pg_isready",
            "redis-cli ping",
            "docker inspect",
        ):
            self.assertNotIn(value, self.workflow)

    def test_settings_discovery_requires_one_existing_boolean_key(self):
        with tempfile.TemporaryDirectory() as directory:
            home = Path(directory)
            path = home / retirement.SETTINGS_RELATIVE_PATHS[0]
            path.parent.mkdir(parents=True)
            path.write_text(
                json.dumps({"KubernetesEnabled": True, "untouched": {"x": 1}}),
                encoding="utf-8",
            )
            with mock.patch.object(retirement, "_reject_symlink_components"):
                target = retirement.discover_settings(home)
                retirement.atomically_set_kubernetes_enabled(target, False)
            updated = json.loads(path.read_text(encoding="utf-8"))
            self.assertIs(updated["KubernetesEnabled"], False)
            self.assertEqual(updated["untouched"], {"x": 1})

            path.write_text(
                json.dumps(
                    {"KubernetesEnabled": True, "kubernetesEnabled": True}
                ),
                encoding="utf-8",
            )
            with mock.patch.object(retirement, "_reject_symlink_components"):
                with self.assertRaisesRegex(retirement.RetirementError, "ambiguous"):
                    retirement.discover_settings(home)

    def test_settings_and_backup_symlinks_fail_closed(self):
        with tempfile.TemporaryDirectory() as directory:
            home = Path(directory)
            real = home / "real-settings.json"
            real.write_text(json.dumps({"KubernetesEnabled": True}), encoding="utf-8")
            path = home / retirement.SETTINGS_RELATIVE_PATHS[0]
            path.parent.mkdir(parents=True)
            path.symlink_to(real)
            with self.assertRaisesRegex(retirement.RetirementError, "symbolic link"):
                retirement.discover_settings(home)

            real_root = home / "backup-real"
            real_root.mkdir()
            linked_root = home / "backup-link"
            linked_root.symlink_to(real_root, target_is_directory=True)
            with self.assertRaisesRegex(retirement.RetirementError, "symbolic link"):
                retirement._prepare_backup_directory(home, linked_root, "123-1")

    def test_digest_ignores_status_but_changes_with_desired_resource_version(self):
        inventory = empty_inventory()
        deployment = item(
            "Deployment",
            "backend",
            spec_value={"replicas": 1, "selector": {"matchLabels": {"app": "api"}}},
        )
        inventory["resources"]["deployments.apps"]["items"] = [deployment]
        first = retirement.inventory_digest(
            inventory["namespaceManifest"], inventory["resources"], [], []
        )
        changed_status = copy.deepcopy(deployment)
        changed_status["status"]["readyReplicas"] = 0
        inventory["resources"]["deployments.apps"]["items"] = [changed_status]
        self.assertEqual(
            first,
            retirement.inventory_digest(
                inventory["namespaceManifest"], inventory["resources"], [], []
            ),
        )
        changed_status["metadata"]["resourceVersion"] = "2"
        self.assertNotEqual(
            first,
            retirement.inventory_digest(
                inventory["namespaceManifest"], inventory["resources"], [], []
            ),
        )

    def test_apply_requires_external_digest_equal_to_current_preflight(self):
        digest = "a" * 64
        with tempfile.TemporaryDirectory() as directory:
            report = Path(directory) / "report.json"
            report.write_text(
                json.dumps({"mode": "preflight", "inventoryDigest": digest}),
                encoding="utf-8",
            )
            retirement._verify_expected_report(report, digest, digest)
            with self.assertRaisesRegex(retirement.RetirementError, "changed"):
                retirement._verify_expected_report(report, digest, "b" * 64)
            with self.assertRaisesRegex(retirement.RetirementError, "64-character"):
                retirement._verify_expected_report(report, digest, "not-a-digest")

    def test_unknown_or_unstoppable_workloads_make_plan_not_ready(self):
        inventory = empty_inventory()
        inventory["foreignUserWorkloads"] = [
            {"namespace": "default", "kind": "Deployment", "name": "unknown"}
        ]
        inventory["unsupportedTargetWorkloads"] = [
            {"namespace": "buddystudy", "kind": "DaemonSet", "name": "unknown"}
        ]
        inventory["activeJobs"] = [
            {"namespace": "buddystudy", "kind": "Job", "name": "running"}
        ]
        settings = retirement.SettingsTarget(Path("settings-store.json"), "KubernetesEnabled", True)
        report = retirement.build_preflight_report(
            inventory,
            settings,
            {
                "kubernetesRunning": 1,
                "kubernetesTotal": 2,
                "nonKubernetes": {},
                "volumes": [],
                "networks": {},
            },
        )
        self.assertFalse(report["ready"])
        self.assertEqual(
            set(report["blockers"]),
            {"foreign-user-workloads", "unsupported-target-workloads", "active-jobs"},
        )

    def test_default_coordinator_is_preserved_in_plan_and_rollback_state(self):
        inventory = empty_inventory()
        backend = item(
            "Deployment",
            "backend",
            spec_value={"replicas": 2, "selector": {"matchLabels": {"app": "backend"}}},
        )
        coordinator = item(
            "Deployment",
            "buddystudy-redis-stream-coordinator",
            namespace="default",
            spec_value={"replicas": 1, "selector": {"matchLabels": {"app": "coordinator"}}},
        )
        inventory["resources"]["deployments.apps"]["items"] = [backend]
        inventory["pods"] = {
            "items": [
                {
                    "metadata": {"name": "backend-1", "labels": {"app": "backend"}},
                    "spec": {"containers": []},
                    "status": {"containerStatuses": []},
                }
            ]
        }
        inventory["auxiliaryWorkloads"] = [coordinator]
        inventory["auxiliaryPods"] = {
            "items": [
                {"metadata": {"name": "coordinator-1", "labels": {"app": "coordinator"}}}
            ]
        }
        state = retirement.workload_state(inventory)
        self.assertEqual(state["writerDeployments"], {"backend": 2})
        self.assertEqual(
            state["auxiliaryDeployments"],
            [{"namespace": "default", "name": "buddystudy-redis-stream-coordinator", "replicas": 1}],
        )

    def test_database_targets_require_running_container(self):
        pod = {
            "metadata": {"name": "db-0"},
            "spec": {"containers": [{"name": "db", "image": "postgres:16-alpine"}]},
            "status": {"containerStatuses": [{"name": "db", "state": {"running": {"startedAt": "now"}}}]},
        }
        self.assertEqual(
            retirement.database_targets({"items": [pod]}),
            [retirement.DatabaseTarget("postgresql", "db-0", "db")],
        )
        pod["status"]["containerStatuses"][0]["state"] = {"waiting": {"reason": "CrashLoopBackOff"}}
        self.assertEqual(retirement.database_targets({"items": [pod]}), [])
        self.assertEqual(retirement.stopped_database_containers({"items": [pod]})[0]["kind"], "postgresql")

    def test_redis_backup_runs_remote_rdb_check_before_copying(self):
        class Cluster:
            def exec_arguments(self, _pod, _container, remote):
                return remote

        class Runner:
            def __init__(self):
                self.command = None

            def stream_raw(self, command, destination, **_kwargs):
                self.command = command
                destination.write_bytes(b"REDIS0009")

        runner = Runner()
        with tempfile.TemporaryDirectory() as directory:
            retirement._backup_databases(
                Cluster(),
                runner,
                [retirement.DatabaseTarget("redis", "redis-0", "redis")],
                Path(directory),
            )
        remote_script = runner.command[-1]
        self.assertIn("command -v redis-check-rdb >/dev/null", remote_script)
        self.assertLess(
            remote_script.index('redis-check-rdb "$dir/$file" >/dev/null'),
            remote_script.index('exec cat "$dir/$file"'),
        )

    def test_streaming_gzip_timeout_interrupts_blocking_copy(self):
        runner = retirement.CommandRunner()
        with tempfile.TemporaryDirectory() as directory:
            destination = Path(directory) / "slow.gz"
            started = time.monotonic()
            with self.assertRaisesRegex(retirement.RetirementError, "timed out"):
                runner.stream_to_gzip(
                    (sys.executable, "-c", "import time; time.sleep(30)"),
                    destination,
                    timeout=1,
                )
            self.assertLess(time.monotonic() - started, 5)

    def test_streaming_interrupt_kills_and_reaps_child(self):
        runner = retirement.CommandRunner()
        real_popen = subprocess.Popen
        children = []

        def capture_child(*arguments, **kwargs):
            child = real_popen(*arguments, **kwargs)
            children.append(child)
            return child

        with tempfile.TemporaryDirectory() as directory, mock.patch.object(
            retirement.subprocess, "Popen", side_effect=capture_child
        ), mock.patch.object(
            retirement.shutil,
            "copyfileobj",
            side_effect=retirement.RetirementError("synthetic interruption"),
        ):
            with self.assertRaisesRegex(
                retirement.RetirementError, "synthetic interruption"
            ):
                runner.stream_to_gzip(
                    (sys.executable, "-c", "import time; time.sleep(30)"),
                    Path(directory) / "interrupted.gz",
                    timeout=30,
                )

        self.assertEqual(len(children), 1)
        self.assertIsNotNone(children[0].returncode)

    def test_raw_stream_interrupt_kills_and_reaps_child(self):
        class InterruptedProcess:
            def __init__(self):
                self.killed = False
                self.waited_after_kill = False

            def poll(self):
                return None if not self.killed else -9

            def kill(self):
                self.killed = True

            def wait(self, timeout=None):
                if not self.killed:
                    raise retirement.RetirementError("synthetic interruption")
                self.waited_after_kill = True
                return -9

        child = InterruptedProcess()
        with tempfile.TemporaryDirectory() as directory, mock.patch.object(
            retirement.subprocess, "Popen", return_value=child
        ):
            with self.assertRaisesRegex(
                retirement.RetirementError, "synthetic interruption"
            ):
                retirement.CommandRunner().stream_raw(
                    ("unused-command",),
                    Path(directory) / "interrupted.tar",
                    timeout=30,
                )
        self.assertTrue(child.killed)
        self.assertTrue(child.waited_after_kill)

    def test_seal_startup_interrupt_kills_and_reaps_tar_child(self):
        class RunningProcess:
            def __init__(self):
                self.stdout = io.BytesIO(b"")
                self.killed = False
                self.waited = False

            def poll(self):
                return None if not self.killed else -9

            def kill(self):
                self.killed = True

            def wait(self, timeout=None):
                self.waited = True
                return -9

        tar_child = RunningProcess()
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            payload = root / "private-staging"
            payload.mkdir(mode=0o700)
            (payload / "manifest.json").write_text("{}", encoding="utf-8")
            with mock.patch.object(
                retirement.subprocess,
                "Popen",
                side_effect=[
                    tar_child,
                    retirement.RetirementError("synthetic seal interruption"),
                ],
            ):
                with self.assertRaisesRegex(
                    retirement.RetirementError, "synthetic seal interruption"
                ):
                    retirement._seal_payload(
                        payload,
                        root / "backup.tar.enc",
                        secret="s" * 64,
                        timeout=30,
                    )
        self.assertTrue(tar_child.killed)
        self.assertTrue(tar_child.waited)

    def test_seal_verification_interrupt_kills_and_reaps_decrypt_child(self):
        class CompletedProcess:
            def __init__(self, stdout=None):
                self.stdout = stdout

            def poll(self):
                return 0

            def kill(self):
                raise AssertionError("completed child must not be killed")

            def wait(self, timeout=None):
                return 0

        class RunningProcess:
            def __init__(self):
                self.stdout = io.BytesIO(b"archive")
                self.killed = False
                self.waited = False

            def poll(self):
                return None if not self.killed else -9

            def kill(self):
                self.killed = True

            def wait(self, timeout=None):
                self.waited = True
                return -9

        decrypt_child = RunningProcess()
        calls = 0

        def create_process(arguments, **kwargs):
            nonlocal calls
            calls += 1
            if calls == 1:
                return CompletedProcess(io.BytesIO(b"archive"))
            if calls == 2:
                kwargs["stdout"].write(b"encrypted")
                return CompletedProcess()
            if calls == 3:
                return decrypt_child
            raise retirement.RetirementError("synthetic verify interruption")

        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            payload = root / "private-staging"
            payload.mkdir(mode=0o700)
            (payload / "manifest.json").write_text("{}", encoding="utf-8")
            with mock.patch.object(
                retirement.subprocess, "Popen", side_effect=create_process
            ):
                with self.assertRaisesRegex(
                    retirement.RetirementError, "synthetic verify interruption"
                ):
                    retirement._seal_payload(
                        payload,
                        root / "backup.tar.enc",
                        secret="s" * 64,
                        timeout=30,
                    )
        self.assertTrue(decrypt_child.killed)
        self.assertTrue(decrypt_child.waited)

    def test_private_bundle_is_encrypted_hmac_verified_and_plaintext_removed(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            payload = root / "private-staging"
            payload.mkdir(mode=0o700)
            (payload / "secret.json").write_text(
                '{"token":"must-not-remain-plaintext"}', encoding="utf-8"
            )
            encrypted = root / "backup.tar.enc"
            retirement._seal_payload(payload, encrypted, secret="x" * 64, timeout=30)
            self.assertFalse(payload.exists())
            self.assertTrue(encrypted.is_file())
            self.assertTrue(encrypted.with_suffix(".enc.hmac").is_file())
            self.assertNotIn(b"must-not-remain-plaintext", encrypted.read_bytes())

    def test_settings_replace_failure_is_treated_as_possible_mutation(self):
        source = self.helper
        assignment = source.index("settings_may_have_changed = True")
        mutation = source.index("atomically_set_kubernetes_enabled(settings, False)")
        self.assertLess(assignment, mutation)
        self.assertIn("_restore_docker_raw_clone", source)
        self.assertIn("atomically_restore_bytes", source)
        self.assertIn('final_inventory["volumes"] != baseline["volumes"]', source)
        self.assertIn('final_inventory["networks"] != baseline["networks"]', source)

    def test_retirement_order_quiesces_writers_before_data_and_docker(self):
        body = self.helper[self.helper.index("def retire(") :]
        ordered = (
            "_create_private_staging(",
            "_revalidate_before_mutation(",
            "_suspend_cronjobs(cluster, state)",
            "_require_no_active_jobs_after_cron_suspend(cluster)",
            "_scale_writers(cluster, state)",
            "_wait_for_writers_scaled_down(cluster, state)",
            "_backup_databases(cluster, runner, databases, payload)",
            "_scale_data_workloads(cluster, state)",
            "_wait_for_all_scaled_down(cluster, state)",
            "_archive_quiesced_host_paths(",
            "_seal_payload(",
            "stop_method = desktop.stop()",
            "raw_clone = _clone_docker_raw(",
            "atomically_set_kubernetes_enabled(settings, False)",
        )
        positions = [body.index(value) for value in ordered]
        self.assertEqual(positions, sorted(positions))

    def test_active_job_after_cron_suspend_blocks_writer_scaling(self):
        class Cluster:
            def __init__(self):
                self.patches = []

            def patch(self, resource, name, value, **_kwargs):
                self.patches.append((resource, name, value))

            def list_resource(self, resource, **kwargs):
                self.assert_all_namespaces = kwargs.get("all_namespaces")
                self.assert_resource = resource
                return {
                    "items": [
                        {
                            "metadata": {
                                "namespace": "buddystudy",
                                "name": "raced-job",
                            },
                            "status": {"active": 1},
                        }
                    ]
                }

        cluster = Cluster()
        state = {
            "cronjobs": {"question-scheduler": False},
            "writerDeployments": {"backend": 1},
            "writerStatefulsets": {},
            "auxiliaryDeployments": [],
        }
        retirement._suspend_cronjobs(cluster, state)
        with self.assertRaisesRegex(retirement.RetirementError, "became active"):
            retirement._require_no_active_jobs_after_cron_suspend(cluster)
        self.assertEqual(
            cluster.patches,
            [
                (
                    "cronjob.batch",
                    "question-scheduler",
                    {"spec": {"suspend": True}},
                )
            ],
        )
        self.assertEqual(cluster.assert_resource, "jobs.batch")
        self.assertTrue(cluster.assert_all_namespaces)

    def test_last_mutation_preflight_rechecks_digest_and_blockers(self):
        start = self.helper.index("def _revalidate_before_mutation(")
        end = self.helper.index("def build_preflight_report(", start)
        body = self.helper[start:end]
        ordered = (
            "inventory = collect_inventory(cluster)",
            "_verify_expected_report(expected_report",
            "current_settings = discover_settings(home)",
            "current_docker = desktop.inventory()",
            "report = build_preflight_report(",
            'if not report["ready"]:',
        )
        positions = [body.index(value) for value in ordered]
        self.assertEqual(positions, sorted(positions))

    def test_reverse_rollback_preserves_disabled_raw_and_restores_clone(self):
        class CopyOnWriteRunner:
            def run(self, arguments, **_kwargs):
                self.assert_command = tuple(arguments)
                if tuple(arguments[:2]) == ("/bin/cp", "-c"):
                    import shutil

                    shutil.copy2(arguments[2], arguments[3])
                    return subprocess.CompletedProcess(arguments, 0, b"", b"")
                raise AssertionError(arguments)

        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source = root / "Docker.raw"
            clone = root / "Docker.raw.apfs-clone"
            source.write_bytes(b"disabled-state")
            clone.write_bytes(b"original-state")
            secret = "z" * 64
            expected = retirement._hmac_digest(clone, secret)
            with mock.patch.object(retirement, "_reject_symlink_components"):
                preserved = retirement._restore_docker_raw_clone(
                    CopyOnWriteRunner(),
                    source=source,
                    clone=clone,
                    expected_hmac=expected,
                    destination=root,
                    secret=secret,
                )
            self.assertEqual(source.read_bytes(), b"original-state")
            self.assertEqual(preserved.read_bytes(), b"disabled-state")
            self.assertEqual(clone.read_bytes(), b"original-state")

    def test_reverse_rollback_rename_failure_keeps_original_raw_available(self):
        class CopyOnWriteRunner:
            def run(self, arguments, **_kwargs):
                import shutil

                shutil.copy2(arguments[2], arguments[3])
                return subprocess.CompletedProcess(arguments, 0, b"", b"")

        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source = root / "Docker.raw"
            clone = root / "Docker.raw.apfs-clone"
            source.write_bytes(b"disabled-state")
            clone.write_bytes(b"original-state")
            secret = "z" * 64
            expected = retirement._hmac_digest(clone, secret)
            real_replace = os.replace
            replace_count = 0

            def interrupted_replace(current, next_path):
                nonlocal replace_count
                replace_count += 1
                if replace_count == 2:
                    raise retirement.RetirementError("synthetic interruption")
                return real_replace(current, next_path)

            with mock.patch.object(retirement, "_reject_symlink_components"), mock.patch.object(
                retirement.os, "replace", side_effect=interrupted_replace
            ):
                with self.assertRaisesRegex(retirement.RetirementError, "interruption"):
                    retirement._restore_docker_raw_clone(
                        CopyOnWriteRunner(),
                        source=source,
                        clone=clone,
                        expected_hmac=expected,
                        destination=root,
                        secret=secret,
                    )
            self.assertEqual(source.read_bytes(), b"disabled-state")
            self.assertEqual(clone.read_bytes(), b"original-state")

    def test_datafolder_absent_uses_exactly_one_standard_docker_raw(self):
        with tempfile.TemporaryDirectory() as directory:
            home = Path(directory)
            settings_path = home / "settings-store.json"
            settings_path.write_text(
                json.dumps({"KubernetesEnabled": True}), encoding="utf-8"
            )
            raw = (
                home
                / retirement.DOCKER_DATA_RELATIVE_PATH
                / "vms/0/data/Docker.raw"
            )
            raw.parent.mkdir(parents=True)
            raw.write_bytes(b"raw")
            settings = retirement.SettingsTarget(
                settings_path, "KubernetesEnabled", True
            )
            with mock.patch.object(retirement, "_reject_symlink_components"):
                self.assertEqual(retirement._docker_raw_path(settings, home), raw.resolve())
                second = raw.parents[2] / "1/data/Docker.raw"
                second.parent.mkdir(parents=True)
                second.write_bytes(b"raw-two")
                with self.assertRaisesRegex(retirement.RetirementError, "Exactly one"):
                    retirement._docker_raw_path(settings, home)

    def test_context_is_local_docker_desktop_only(self):
        self.assertIn('"https://127.0.0.1:6443"', self.helper)
        self.assertIn('"https://localhost:6443"', self.helper)
        self.assertIn('!= "desktop-linux"', self.helper)
        self.assertIn(
            "A Kubernetes command targeted an unapproved namespace", self.helper
        )

    def test_partial_private_staging_is_removed_if_manifest_backup_fails(self):
        inventory = empty_inventory()
        baseline = {
            "kubernetesTotal": 1,
            "kubernetesRunning": 1,
            "nonKubernetes": {},
            "volumes": [],
            "networks": {},
        }
        with tempfile.TemporaryDirectory() as directory:
            destination = Path(directory) / "retirement-1"
            destination.mkdir(mode=0o700)
            settings = retirement.SettingsTarget(
                Path(directory) / "settings-store.json",
                "KubernetesEnabled",
                True,
            )
            with mock.patch.object(
                retirement, "_backup_manifests", side_effect=OSError("synthetic")
            ):
                with self.assertRaises(OSError):
                    retirement._create_private_staging(
                        destination,
                        settings_original=b'{"KubernetesEnabled":true}',
                        inventory=inventory,
                        state={},
                        settings=settings,
                        baseline=baseline,
                    )
            self.assertFalse((destination / "private-staging").exists())

    def test_external_hostpath_missing_or_symlink_fails_closed(self):
        source = retirement.PvcSource(
            "claim", "pv", Path("/Users/definitely-missing-buddystudy-data")
        )
        with self.assertRaisesRegex(retirement.RetirementError, "missing"):
            retirement._validate_external_host_paths(
                [source], home=Path("/Users/example"), backup_root=Path("/Users/example/backups")
            )

        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            actual = root / "actual"
            actual.mkdir()
            linked = root / "linked"
            linked.symlink_to(actual, target_is_directory=True)
            with self.assertRaisesRegex(retirement.RetirementError, "symbolic link"):
                retirement._safe_host_data_path(
                    linked, home=root / "home", backup_root=root / "backup"
                )

    def test_keychain_recovery_key_is_created_then_read_back(self):
        secret = "k" * 64

        class FakeRunner:
            def __init__(self):
                self.calls = []
                self.lookup_count = 0

            def run(self, arguments, **_kwargs):
                self.calls.append(tuple(arguments))
                if arguments[1] == "find-generic-password":
                    self.lookup_count += 1
                    if self.lookup_count == 1:
                        return subprocess.CompletedProcess(arguments, 44, b"", b"")
                    return subprocess.CompletedProcess(arguments, 0, secret.encode() + b"\n", b"")
                return subprocess.CompletedProcess(arguments, 0, b"", b"")

        runner = FakeRunner()
        retirement._ensure_keychain_recovery_key(runner, secret)
        self.assertEqual(runner.lookup_count, 2)
        self.assertEqual(sum(call[1] == "add-generic-password" for call in runner.calls), 1)
        self.assertIn(retirement.KEYCHAIN_SERVICE, runner.calls[1])

    def test_signal_requests_guarded_rollback(self):
        with self.assertRaisesRegex(retirement.RetirementError, "rollback requested"):
            retirement._handle_termination(15, None)
        self.assertGreaterEqual(retirement.MINIMUM_BACKUP_FREE_BYTES, 12 * 1024**3)

    def test_non_database_statefulset_is_stopped_with_writers(self):
        inventory = empty_inventory()
        worker = item(
            "StatefulSet",
            "worker",
            spec_value={"replicas": 1, "selector": {"matchLabels": {"app": "worker"}}},
        )
        redis = item(
            "StatefulSet",
            "redis",
            spec_value={"replicas": 1, "selector": {"matchLabels": {"app": "redis"}}},
        )
        inventory["resources"]["statefulsets.apps"]["items"] = [worker, redis]
        inventory["pods"] = {
            "items": [
                {
                    "metadata": {"name": "worker-0", "labels": {"app": "worker"}},
                    "spec": {"containers": [{"name": "worker", "image": "example/worker:1"}]},
                    "status": {"containerStatuses": [{"name": "worker", "state": {"running": {}}}]},
                },
                {
                    "metadata": {"name": "redis-0", "labels": {"app": "redis"}},
                    "spec": {"containers": [{"name": "redis", "image": "redis:7-alpine"}]},
                    "status": {"containerStatuses": [{"name": "redis", "state": {"running": {}}}]},
                },
            ]
        }
        state = retirement.workload_state(inventory)
        self.assertEqual(state["writerStatefulsets"], {"worker": 1})
        self.assertEqual(state["dataStatefulsets"], {"redis": 1})

    def test_rendered_summary_never_contains_manifest_or_secret_values(self):
        report = {
            "mode": "preflight",
            "ready": False,
            "blockers": ["foreign-user-workloads"],
            "inventoryDigest": "a" * 64,
            "workloadIdentities": [],
            "pvcIdentities": [],
        }
        summary = retirement._render_summary(report, "success")
        self.assertIn("Desired-state digest", summary)
        self.assertNotIn("data:", summary)
        self.assertNotIn("stringData:", summary)


if __name__ == "__main__":
    unittest.main()
