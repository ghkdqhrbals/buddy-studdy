import importlib.util
import io
import json
import os
import subprocess
import sys
import tempfile
import time
import unittest
from contextlib import redirect_stdout
from pathlib import Path
from unittest import mock


ROOT = Path(__file__).resolve().parents[1]
HELPER = ROOT / "scripts" / "diagnose_macbookair_docker_rca.py"
WORKFLOW = ROOT / ".github/workflows/diagnose-macbookair-docker-rca.yml"
if not WORKFLOW.is_file():
    WORKFLOW = ROOT / "diagnose-macbookair-docker-rca.yml"

spec = importlib.util.spec_from_file_location("macbookair_docker_rca", HELPER)
rca = importlib.util.module_from_spec(spec)
assert spec.loader is not None
sys.modules[spec.name] = rca
spec.loader.exec_module(rca)


class MacBookAirDockerRCATests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.helper = HELPER.read_text(encoding="utf-8")
        cls.workflow = WORKFLOW.read_text(encoding="utf-8")

    def test_workflow_is_manual_read_only_and_on_normal_air_labels(self):
        self.assertIn("workflow_dispatch:", self.workflow)
        self.assertNotIn("schedule:", self.workflow)
        self.assertIn(
            "runs-on: [self-hosted, macOS, ARM64, macbook-air, buddystudy]",
            self.workflow,
        )
        self.assertIn("timeout-minutes: 8", self.workflow)
        self.assertIn("diagnose_macbookair_docker_rca.py collect", self.workflow)
        self.assertIn("diagnose_macbookair_docker_rca.py render-summary", self.workflow)

    def test_fixed_probe_commands_are_read_only_and_target_docker_desktop(self):
        commands = []

        def unavailable(command, **_kwargs):
            commands.append(tuple(command))
            return rca.CommandResult("unavailable")

        with mock.patch.object(rca, "_run_fixed", side_effect=unavailable), redirect_stdout(io.StringIO()):
            report = rca.collect_report()
        self.assertTrue(report["readOnly"])
        flattened = [" ".join(command).lower() for command in commands]
        self.assertTrue(any(" stats --no-stream " in f" {value} " for value in flattened))
        self.assertTrue(any(" ps --all " in f" {value} " for value in flattened))
        self.assertTrue(any("kubectl --context docker-desktop get pods" in value for value in flattened))
        self.assertTrue(any("desktop logs -s -6h -p 0" in value for value in flattened))
        self.assertTrue(any("/usr/bin/log show --last 6h" in value for value in flattened))
        self.assertIn("/usr/bin/sw_vers -productversion", flattened)
        self.assertIn("/usr/bin/sw_vers -buildversion", flattened)
        self.assertTrue(
            any(
                "/usr/libexec/plistbuddy -c print :cfbundleshortversionstring /applications/docker.app/contents/info.plist"
                in value
                for value in flattened
            )
        )
        for command in commands:
            if command[0] == rca.DOCKER_CLI and len(command) > 1 and command[1] != "desktop":
                self.assertIn("docker-desktop", command)
        for forbidden in (
            " restart ", " stop ", " prune ", " rm ", " delete ", " apply ",
            " rollout ", " scale ", " create ", " run ", " diagnose gather",
            " support ", " upload ", " inspect ",
        ):
            self.assertFalse(any(forbidden in f" {value} " for value in flattened), forbidden)

    def test_formats_never_request_ids_env_mounts_paths_or_arbitrary_labels(self):
        combined = f"{rca.STATS_FORMAT}\n{rca.PS_FORMAT}"
        for forbidden in (".ID", ".Image", ".Command", ".Mounts", ".Labels", ".Ports", ".Networks"):
            self.assertNotIn(forbidden, combined)
        self.assertIn('com.docker.compose.project', rca.PS_FORMAT)
        self.assertIn('io.kubernetes.pod.name', rca.PS_FORMAT)

    def test_stats_parser_retains_only_sanitized_resource_values(self):
        output = json.dumps(
            {
                "name": "buddystudy-api-dashboard",
                "cpu": "12.50%",
                "memory": "1.50GiB / 7.50GiB",
                "memoryPercent": "20.00%",
                "pids": "42",
                "unexpected": "raw-secret",
            }
        )
        parsed = rca.parse_docker_stats(output)
        self.assertEqual(parsed["totalMemoryUsageBytes"], int(1.5 * 1024**3))
        self.assertEqual(parsed["topMemoryWorkloads"][0]["memoryLimitBytes"], int(7.5 * 1024**3))
        self.assertEqual(parsed["topMemoryWorkloads"][0]["workload"], "buddystudy-api-dashboard")
        self.assertNotIn("raw-secret", json.dumps(parsed))

    def test_os_version_and_build_are_strictly_allowlisted(self):
        self.assertEqual(rca.parse_os_version("15.6\n"), "15.6")
        self.assertEqual(rca.parse_os_build("24G90\n"), "24G90")
        self.assertIsNone(rca.parse_os_version("15.6 /Users/private"))
        self.assertIsNone(rca.parse_os_build("24G90 device-secret"))

    def test_workload_parser_allows_only_ownership_labels(self):
        output = json.dumps(
            {
                "name": "k8s_api_pod_namespace_deadbeefdeadbeef_0",
                "state": "running",
                "project": "buddystudy",
                "service": "api",
                "k8sContainer": "api",
                "k8sPod": "api-pod",
                "k8sNamespace": "buddystudy",
                "secretLabel": "do-not-retain",
            }
        )
        parsed = rca.parse_docker_ps(output)
        encoded = json.dumps(parsed)
        self.assertIn("buddystudy", encoded)
        self.assertNotIn("deadbeefdeadbeef", encoded)
        self.assertNotIn("do-not-retain", encoded)

    def test_kubernetes_restart_and_warning_parsers_discard_messages(self):
        pods = rca.parse_kubernetes_pods(
            "buddystudy\tapi-0\tRunning\tapi,7,CrashLoopBackOff,OOMKilled,2026-08-23T02:01:00Z;\n"
        )
        self.assertEqual(pods["topRestartedPods"][0]["restartCount"], 7)
        self.assertEqual(
            pods["topRestartedPods"][0]["reasons"],
            {"CrashLoopBackOff": 1, "OOMKilled": 1},
        )
        self.assertEqual(
            pods["topRestartedPods"][0]["latestTerminationTime"],
            "2026-08-23T02:01:00Z",
        )
        events = rca.parse_kubernetes_events(
            "buddystudy Pod api-0 BackOff 19 2026-08-23T02:02:00Z\n"
        )
        self.assertEqual(events["reasonCounts"], {"BackOff": 19})

    def test_log_parser_aggregates_without_retaining_raw_lines(self):
        raw = (
            "2026-08-23T01:02:03Z com.docker.backend out of memory "
            "footprint 96.34 GB path=/Users/private token=secret-value\n"
        )
        parsed = rca.parse_desktop_logs(raw)
        encoded = json.dumps(parsed)
        self.assertEqual(parsed["categoryCounts"]["host-oom"], 1)
        self.assertGreater(parsed["maxReportedBytes"], 96 * 1000**3)
        self.assertIn("com.docker.backend", encoded)
        for forbidden in ("/Users/private", "secret-value", "footprint"):
            self.assertNotIn(forbidden, encoded)

    def test_desktop_log_classifier_covers_restart_cause_families(self):
        cases = {
            "error-fatal": "fatal error in backend",
            "no-space": "write failed: no space left on device",
            "io-error": "disk I/O error while syncing",
            "read-only-fs": "open failed: read-only file system",
            "config-invalid": "invalid configuration for engine",
            "lifecycle": "Docker Desktop shutting down",
            "startup-failure": "backend failed to start",
            "daemon-unavailable": "cannot connect to the Docker daemon",
            "socket-error": "socket connection refused",
            "timeout-deadline": "context deadline exceeded",
            "vm-crash": "vfkit virtual machine exited unexpectedly",
            "kubernetes-etcd": "etcd server unavailable for kubernetes",
        }
        for expected, message in cases.items():
            with self.subTest(expected=expected):
                parsed = rca.parse_desktop_logs(f"2026-08-23T01:02:03Z {message}\n")
                self.assertIn(expected, parsed["categoryCounts"])

    def test_desktop_log_signatures_are_sanitized_and_aggregate_volatiles(self):
        raw = (
            '2026-08-23T01:02:03Z component=com.docker.backend fatal timeout id='
            '123e4567-e89b-12d3-a456-426614174000 path=/Users/private token=alpha\n'
            '2026-08-23T01:02:04Z component=com.docker.backend fatal timeout id='
            '223e4567-e89b-12d3-a456-426614174999 path=/Users/private token=alpha\n'
        )
        parsed = rca.parse_desktop_logs(raw)
        self.assertEqual(len(parsed["classifiedSignals"]), 1)
        signal = parsed["classifiedSignals"][0]
        self.assertEqual(
            set(signal),
            {"categories", "unit", "component", "firstTime", "latestTime", "fingerprint", "count"},
        )
        self.assertEqual(signal["unit"], "com.docker.backend")
        self.assertEqual(signal["component"], "com.docker.backend")
        self.assertEqual(signal["count"], 2)
        self.assertEqual(signal["firstTime"], "2026-08-23T01:02:03Z")
        self.assertEqual(signal["latestTime"], "2026-08-23T01:02:04Z")
        self.assertRegex(signal["fingerprint"], r"^[0-9a-f]{16}$")
        encoded = json.dumps(parsed)
        for forbidden in ("/Users/private", "token=alpha", "123e4567", "fatal timeout"):
            self.assertNotIn(forbidden, encoded)

    def test_unified_log_parser_keeps_safe_process_time_and_aggregate_only(self):
        output = json.dumps(
            {
                "timestamp": "2026-08-23 02:03:04.000+0900",
                "process": "memorystatusd",
                "processImagePath": "/private/sensitive/path",
                "eventMessage": "Docker Desktop memory pressure 96.34 GB user=device-secret",
            }
        )
        parsed = rca.parse_unified_logs(output)
        encoded = json.dumps(parsed)
        self.assertEqual(parsed["matchedLineCount"], 1)
        self.assertNotIn("/private/sensitive/path", encoded)
        self.assertNotIn("device-secret", encoded)

    def test_unified_predicate_includes_generic_system_memory_sources(self):
        system_predicate = rca.UNIFIED_SYSTEM_MEMORY_PREDICATE
        for source in ("kernel", "memorystatusd", "runningboardd", "watchdogd"):
            self.assertIn(source, system_predicate)
        self.assertIn('eventMessage CONTAINS[c] "memory pressure"', system_predicate)
        login_predicate = rca.UNIFIED_LOGINWINDOW_PREDICATE
        self.assertIn('process == "loginwindow"', login_predicate)
        for phrase in ("Sampling App", "setting rSize", "app size string", "application memory"):
            self.assertIn(phrase, login_predicate)
        generic = json.dumps(
            {
                "timestamp": "2026-08-23 02:03:04.000+0900",
                "process": "memorystatusd",
                "eventMessage": "system-wide memory pressure entered critical state",
            }
        )
        parsed = rca.parse_unified_logs(generic)
        self.assertEqual(parsed["categoryCounts"], {"memory-pressure": 1})
        low_memory = json.dumps(
            {
                "timestamp": "2026-08-23 02:03:05.000+0900",
                "process": "loginwindow",
                "eventMessage": "Your system has run out of application memory",
            }
        )
        self.assertEqual(
            rca.parse_unified_logs(low_memory)["categoryCounts"],
            {"memory-pressure": 1},
        )

    def test_loginwindow_size_requires_nearby_docker_sampling_record(self):
        lines = (
            json.dumps(
                {
                    "timestamp": "2026-08-23 02:00:00.000+0900",
                    "process": "loginwindow",
                    "eventMessage": "Sampling App: Docker Desktop",
                }
            )
            + "\n"
            + json.dumps(
                {
                    "timestamp": "2026-08-23 02:00:01.000+0900",
                    "process": "loginwindow",
                    "eventMessage": "setting rSize: 103444070400",
                }
            )
            + "\n"
            + json.dumps(
                {
                    "timestamp": "2026-08-23 02:00:02.000+0900",
                    "process": "loginwindow",
                    "eventMessage": "app size string: 96.34 GB",
                }
            )
        )
        parsed = rca.parse_loginwindow_logs(lines)
        evidence = parsed["applicationSizeEvidence"]
        self.assertEqual(len(evidence), 2)
        self.assertEqual(evidence[0]["reportedBytes"], 103_444_070_400)
        self.assertEqual(evidence[0]["component"], "Docker-Desktop")
        self.assertNotIn("setting rSize", json.dumps(parsed))
        unpaired = json.dumps(
            {
                "timestamp": "2026-08-23 02:00:02.000+0900",
                "process": "loginwindow",
                "eventMessage": "app size string: 96.34 GB",
            }
        )
        self.assertEqual(
            rca.parse_loginwindow_logs(unpaired)["applicationSizeEvidence"], []
        )

    def test_nested_desktop_status_shape_keeps_only_known_states(self):
        parsed = rca.parse_desktop_status(
            json.dumps(
                {
                    "vm": {"status": "running", "path": "/private/raw"},
                    "containerEngine": {"state": "stopped", "id": "secret"},
                    "kubernetes": {"status": "starting"},
                }
            )
        )
        self.assertEqual(
            parsed,
            {
                "desktopVirtualMachine": "running",
                "dockerEngine": "stopped",
                "kubernetesEngine": "starting",
            },
        )

    def test_virtualization_vm_process_is_included_with_elapsed_bounds(self):
        parsed = rca.parse_docker_processes(
            "1153433 90000000 01:02:03 /Applications/Docker.app/Contents/MacOS/com.apple.Virtualization.VirtualMachine\n"
            "1000 2000 00:00:30 /Applications/Docker.app/Contents/MacOS/com.docker.backend\n"
        )
        components = {item["component"]: item for item in parsed["components"]}
        vm = components["com.apple.Virtualization.VirtualMachine"]
        self.assertEqual(vm["rssBytes"], 1_153_433 * 1024)
        self.assertEqual(vm["oldestElapsedSeconds"], 3723)
        self.assertEqual(vm["newestElapsedSeconds"], 3723)

    def test_diagnostic_candidate_discovery_is_streamed_and_bounded(self):
        self.assertNotIn("list(os.scandir", self.helper)
        self.assertEqual(rca.DIAGNOSTIC_ENTRY_LIMIT_PER_ROOT, 4096)
        self.assertEqual(rca.DIAGNOSTIC_CANDIDATE_POOL_LIMIT, 128)
        self.assertIn("heapq.heapreplace", self.helper)

    def test_jetsam_snapshot_uses_same_snapshot_pages_and_never_sums_lifetime_max(self):
        raw = (
            json.dumps({"timestamp": "2026-08-23T02:00:00+09:00", "largestProcess": "Docker Desktop"})
            + "\n"
            + json.dumps(
                {
                    "pageSize": 16384,
                    "processes": [
                        {
                            "name": "Docker Desktop",
                            "rpages": 6_000_000,
                            "lifetimeMax": 7_000_000,
                            "coalition": 77,
                            "reason": "per-process-limit",
                        },
                        {
                            "name": "private-user-process",
                            "rpages": 100,
                            "lifetimeMax": 9_000_000,
                            "coalition": 77,
                        },
                    ],
                }
            )
        )
        snapshot = rca._diagnostic_snapshot(raw, file_name="JetsamEvent-test.ips", modified_at=0)
        self.assertEqual(snapshot["largestProcessCurrentBytes"], 6_000_000 * 16384)
        self.assertEqual(snapshot["coalitions"][0]["currentBytes"], 6_000_100 * 16384)
        self.assertEqual(
            snapshot["dockerNamedProcessCurrentBytes"], 6_000_000 * 16384
        )
        self.assertEqual(
            snapshot["dockerContainingCoalitions"][0]["currentBytes"],
            6_000_100 * 16384,
        )
        self.assertEqual(snapshot["processes"][0]["lifetimeMaxBytes"], 7_000_000 * 16384)
        encoded = json.dumps(snapshot)
        self.assertNotIn("private-user-process", encoded)
        self.assertIn("other-", encoded)
        self.assertNotIn('"coalition": 77', encoded)
        self.assertNotIn("dockerCurrentBytes", encoded)

    def test_diagnostic_report_child_protocol_rejects_raw_or_unknown_payload(self):
        self.assertIsNone(rca.parse_diagnostic_reports_child('{"raw":"secret"}'))
        value = rca.parse_diagnostic_reports_child(
            json.dumps({"mode": "sanitized-diagnostic-reports", "result": {"snapshots": []}})
        )
        self.assertEqual(value, {"snapshots": []})

    def test_incident_time_correlates_with_nearest_desktop_or_restart_churn(self):
        report = {
            "diagnosticReports": {
                "snapshots": [{"timestamp": "2026-08-23T02:00:00Z"}]
            },
            "desktopLogsCurrentBoot": {
                "signalTimes": ["2026-08-23T01:59:40Z"]
            },
            "kubernetesPods": {
                "topRestartedPods": [
                    {"latestTerminationTime": "2026-08-23T01:59:55Z"}
                ]
            },
            "kubernetesEvents": {"topWarnings": []},
        }
        rca._add_incident_correlations(report)
        self.assertEqual(
            report["incidentCorrelations"],
            [
                {
                    "incidentTime": "2026-08-23T02:00:00Z",
                    "nearestChurnTime": "2026-08-23T01:59:55Z",
                    "source": "kubernetes-termination",
                    "absoluteDeltaSeconds": 5,
                }
            ],
        )

    def test_failed_command_output_is_discarded_before_parsing(self):
        with mock.patch.object(
            rca,
            "_run_fixed",
            return_value=rca.CommandResult("command-failed", "raw-secret"),
        ), mock.patch.object(rca, "parse_desktop_logs") as parser, redirect_stdout(io.StringIO()):
            report = {"probes": {}}
            rca._record_probe(report, "logs", ("/bin/false",), parser, "logs")
        parser.assert_not_called()
        self.assertNotIn("logs", report)

    def test_private_report_is_mode_0600_and_contains_only_parsed_data(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "report.json"
            rca._write_report(path, {"mode": "docker-rca", "readOnly": True})
            self.assertEqual(path.stat().st_mode & 0o777, 0o600)
            self.assertEqual(json.loads(path.read_text()), {"mode": "docker-rca", "readOnly": True})

    def test_summary_does_not_echo_unknown_report_fields(self):
        report = {
            "mode": "docker-rca",
            "probes": {},
            "rawLog": "secret-message",
            "path": "/Users/private",
        }
        summary = rca.render_summary(report, "success")
        self.assertNotIn("secret-message", summary)
        self.assertNotIn("/Users/private", summary)
        self.assertIn("No restart, stop, force-quit", summary)
        self.assertIn("service child processes such as k6", summary)
        self.assertIn("not evidence that no OOM occurred", summary)


if __name__ == "__main__":
    unittest.main()
