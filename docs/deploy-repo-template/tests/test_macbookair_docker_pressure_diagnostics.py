import importlib.util
import io
import json
import os
import stat
import sys
import tempfile
import time
import unittest
from contextlib import redirect_stdout
from pathlib import Path
from unittest import mock


ROOT = Path(__file__).resolve().parents[1]
HELPER = ROOT / "scripts" / "diagnose_macbookair_docker_pressure.py"
WORKFLOW = ROOT / ".github/workflows/diagnose-macbookair-host-pressure.yml"
if not WORKFLOW.is_file():
    WORKFLOW = ROOT / "diagnose-macbookair-host-pressure.yml"

spec = importlib.util.spec_from_file_location("docker_pressure_diagnostics", HELPER)
diagnostics = importlib.util.module_from_spec(spec)
assert spec.loader is not None
sys.modules[spec.name] = diagnostics
spec.loader.exec_module(diagnostics)


def wait_for_pid_exit(pid, timeout=3.0):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        try:
            os.kill(pid, 0)
        except ProcessLookupError:
            return True
        time.sleep(0.02)
    return False


class MacBookAirDockerPressureDiagnosticTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.helper = HELPER.read_text(encoding="utf-8")
        cls.workflow = WORKFLOW.read_text(encoding="utf-8")

    def test_workflow_is_a_separate_manual_host_only_snapshot(self):
        self.assertIn("workflow_dispatch:", self.workflow)
        self.assertNotIn("schedule:", self.workflow)
        self.assertNotIn("inputs.apply", self.workflow)
        self.assertIn("Submit Read-Only Host Pressure Snapshot", self.workflow)
        self.assertIn(
            "diagnose_macbookair_docker_pressure.py collect", self.workflow
        )
        self.assertIn(
            "diagnose_macbookair_docker_pressure.py render-summary", self.workflow
        )

    def test_diagnostic_uses_normal_air_labels_without_retirement_or_fda(self):
        self.assertIn(
            "runs-on: [self-hosted, macOS, ARM64, macbook-air, buddystudy]",
            self.workflow,
        )
        self.assertNotIn("macbook-air-k8s-retirement", self.workflow)
        self.assertNotIn("Full Disk Access", self.workflow)
        self.assertIn(
            'EXPECTED_RUNNER_NAME = "macbook-air-buddystudy"', self.helper
        )
        self.assertIn('os.environ.get("GITHUB_ACTIONS") != "true"', self.helper)

    def test_diagnostic_never_invokes_docker_or_mutation_commands(self):
        commands = []

        def unavailable(command):
            commands.append(tuple(command))
            return diagnostics.CommandResult("unavailable")

        with mock.patch.object(diagnostics, "_run_fixed", side_effect=unavailable):
            report = diagnostics.collect_report()

        self.assertEqual(report["mode"], "pressure-diagnostics")
        self.assertTrue(report["readOnly"])
        self.assertEqual(
            {command[0] for command in commands},
            {"/usr/sbin/sysctl", "/usr/bin/vm_stat", "/usr/bin/memory_pressure", "/bin/df", "/bin/ps"},
        )
        flattened = " ".join(" ".join(command).lower() for command in commands)
        for forbidden in (
            "docker info",
            "docker system",
            "docker stats",
            "docker desktop",
            "prune",
            "restart",
            " stop ",
            "killall",
            "pkill",
            "rm ",
        ):
            self.assertNotIn(forbidden, flattened)
        self.assertNotIn("shell=True", self.helper)

    def test_commands_are_individually_bounded_and_discard_stderr(self):
        self.assertEqual(diagnostics.COMMAND_TIMEOUT_SECONDS, 8)
        self.assertEqual(diagnostics.MAX_CAPTURE_BYTES, 1024 * 1024)
        self.assertIn("time.monotonic() + COMMAND_TIMEOUT_SECONDS", self.helper)
        self.assertIn("stderr=subprocess.DEVNULL", self.helper)
        self.assertIn("start_new_session=True", self.helper)
        self.assertIn("os.read", self.helper)
        self.assertNotIn("process.communicate", self.helper)
        self.assertLess(
            self.helper.index("if len(chunk) > remaining_capacity"),
            self.helper.index("captured.extend(chunk)"),
        )

    def test_failed_command_output_is_never_parsed_or_stored(self):
        report = {"probes": {}}
        parser = mock.Mock(return_value=123)
        with mock.patch.object(
            diagnostics,
            "_run_fixed",
            return_value=diagnostics.CommandResult("command-failed", "123"),
        ), redirect_stdout(io.StringIO()):
            diagnostics._record_probe(
                report,
                "physical-memory",
                ("/usr/sbin/sysctl", "-n", "hw.memsize"),
                parser,
                "physicalMemoryBytes",
            )
        parser.assert_not_called()
        self.assertEqual(report["probes"]["physical-memory"], "command-failed")
        self.assertNotIn("physicalMemoryBytes", report)

    def test_nonzero_command_discards_stdout_and_stderr(self):
        result = diagnostics._run_fixed(
            (
                sys.executable,
                "-c",
                "import sys; print('stdout-secret'); print('stderr-secret', file=sys.stderr); raise SystemExit(7)",
            )
        )
        self.assertEqual(result, diagnostics.CommandResult("command-failed"))

    def test_stdout_cap_is_enforced_during_execution_and_group_is_reaped(self):
        with tempfile.TemporaryDirectory() as directory:
            pid_file = Path(directory) / "pids"
            child_script = (
                "import os,subprocess,sys,time; "
                "child=subprocess.Popen([sys.executable,'-c','import time; time.sleep(60)']); "
                "open(sys.argv[1],'w').write(str(os.getpid())+' '+str(child.pid)); "
                "sys.stdout.buffer.write(b'x'*4096); sys.stdout.buffer.flush(); time.sleep(60)"
            )
            with mock.patch.object(diagnostics, "MAX_CAPTURE_BYTES", 1024):
                started = time.monotonic()
                result = diagnostics._run_fixed(
                    (sys.executable, "-c", child_script, str(pid_file))
                )
                elapsed = time.monotonic() - started
            self.assertEqual(result, diagnostics.CommandResult("oversize"))
            self.assertLess(elapsed, 3)
            pids = [int(value) for value in pid_file.read_text().split()]
            self.assertEqual(len(pids), 2)
            self.assertTrue(all(wait_for_pid_exit(pid) for pid in pids))

    def test_timeout_reaps_the_direct_child(self):
        with tempfile.TemporaryDirectory() as directory:
            pid_file = Path(directory) / "pid"
            child_script = (
                "import os,sys,time; "
                "open(sys.argv[1],'w').write(str(os.getpid())); time.sleep(60)"
            )
            with mock.patch.object(diagnostics, "COMMAND_TIMEOUT_SECONDS", 0.2):
                started = time.monotonic()
                result = diagnostics._run_fixed(
                    (sys.executable, "-c", child_script, str(pid_file))
                )
                elapsed = time.monotonic() - started
            self.assertEqual(result, diagnostics.CommandResult("timeout"))
            self.assertLess(elapsed, 3)
            self.assertTrue(wait_for_pid_exit(int(pid_file.read_text())))

    def test_vm_stat_parser_keeps_only_allowlisted_page_counters(self):
        parsed = diagnostics.parse_vm_stat(
            "Mach Virtual Memory Statistics: (page size of 16384 bytes)\n"
            "Pages free:                               10.\n"
            "Pages active:                             20.\n"
            "Pages wired down:                          5.\n"
            "Translation faults:                999999999.\n"
        )
        self.assertEqual(parsed["pageSizeBytes"], 16384)
        self.assertEqual(parsed["freeBytes"], 10 * 16384)
        self.assertEqual(parsed["activeBytes"], 20 * 16384)
        self.assertEqual(parsed["wiredBytes"], 5 * 16384)
        self.assertNotIn("Translation faults", parsed)

    def test_swap_and_pressure_parsers(self):
        self.assertEqual(
            diagnostics.parse_swap_usage(
                "total = 8192.00M  used = 7168.50M  free = 1023.50M  (encrypted)"
            ),
            {
                "totalBytes": 8192 * 1024**2,
                "usedBytes": int(7168.5 * 1024**2),
                "freeBytes": int(1023.5 * 1024**2),
            },
        )
        self.assertEqual(
            diagnostics.parse_pressure_percentage(
                "System-wide memory free percentage: 3%"
            ),
            3,
        )
        self.assertIsNone(
            diagnostics.parse_pressure_percentage(
                "System-wide memory free percentage: 103%"
            )
        )

    def test_df_parser_discards_filesystem_and_mount_paths(self):
        parsed = diagnostics.parse_df(
            "Filesystem 1024-blocks Used Available Capacity Mounted on\n"
            "/dev/disk3s5 100000000 70000000 30000000 70% /System/Volumes/Data\n"
        )
        self.assertEqual(
            parsed,
            {
                "totalBytes": 100000000 * 1024,
                "usedBytes": 70000000 * 1024,
                "availableBytes": 30000000 * 1024,
            },
        )
        self.assertNotIn("disk", json.dumps(parsed))
        self.assertNotIn("/System", json.dumps(parsed))

    def test_process_parser_reports_basename_groups_without_command_lines(self):
        parsed = diagnostics.parse_processes(
            "100000000 120000000 /Applications/Docker.app/Contents/MacOS/com.docker.backend\n"
            "120000 500000 /Applications/Docker.app/Contents/MacOS/Docker Desktop\n"
            "900000 1200000 /Applications/Safari.app/Contents/MacOS/Safari\n"
        )
        self.assertEqual(parsed["sampleCount"], 3)
        self.assertEqual(parsed["dockerProcessCount"], 2)
        self.assertEqual(parsed["dockerRssBytes"], 100120000 * 1024)
        names = [item["name"] for item in parsed["topRssByExecutable"]]
        self.assertEqual(names[0], "com.docker.backend")
        serialized = json.dumps(parsed)
        self.assertNotIn("/Applications", serialized)
        self.assertNotIn("Contents/MacOS", serialized)

    def test_process_names_are_markdown_safe_and_bounded(self):
        name = diagnostics._safe_process_basename(
            "/private/path/<script>|`secret`$argument" + "x" * 100
        )
        self.assertLessEqual(len(name), 64)
        for unsafe in ("/", "<", ">", "|", "`", "$", "\\"):
            self.assertNotIn(unsafe, name)

    def test_summary_labels_rss_and_virtual_address_space_separately(self):
        report = {
            "mode": "pressure-diagnostics",
            "probes": {"process-rss": "ok"},
            "pressureName": "critical",
            "pressureLevel": 4,
            "processes": {
                "sampleCount": 2,
                "summedRssBytes": 101 * 1024**3,
                "dockerProcessCount": 1,
                "dockerRssBytes": 96 * 1024**3,
                "dockerVirtualBytes": 200 * 1024**3,
                "dockerExecutables": [
                    {
                        "name": "com.docker.backend",
                        "count": 1,
                        "rssBytes": 96 * 1024**3,
                    }
                ],
                "topRssByExecutable": [],
            },
        }
        summary = diagnostics.render_summary(report, "success")
        self.assertIn("Docker-related host RSS: `96.00 GiB`", summary)
        self.assertIn("virtual address space", summary)
        self.assertIn("not resident RAM", summary)
        self.assertIn("Snapshot result: `submitted`", summary)
        self.assertIn("snapshot-submitted", self.helper)
        self.assertIn("No Docker API call", diagnostics.render_summary({}, "failure"))

    def test_report_is_private_and_atomic(self):
        with tempfile.TemporaryDirectory() as directory:
            report_path = Path(directory) / "pressure.json"
            diagnostics._write_report(report_path, {"mode": "pressure-diagnostics"})
            mode = stat.S_IMODE(report_path.stat().st_mode)
            self.assertEqual(mode, 0o600)
            self.assertEqual(
                json.loads(report_path.read_text(encoding="utf-8"))["mode"],
                "pressure-diagnostics",
            )

    def test_host_gate_is_exact_and_fails_closed(self):
        with mock.patch.object(diagnostics.platform, "system", return_value="Darwin"), mock.patch.object(
            diagnostics.platform, "machine", return_value="arm64"
        ), mock.patch.dict(
            os.environ,
            {"GITHUB_ACTIONS": "true", "RUNNER_NAME": diagnostics.EXPECTED_RUNNER_NAME},
            clear=True,
        ):
            diagnostics._ensure_host()

        with mock.patch.object(diagnostics.platform, "system", return_value="Darwin"), mock.patch.object(
            diagnostics.platform, "machine", return_value="arm64"
        ), mock.patch.dict(
            os.environ,
            {"GITHUB_ACTIONS": "true", "RUNNER_NAME": "another-runner"},
            clear=True,
        ):
            with self.assertRaises(diagnostics.DiagnosticError):
                diagnostics._ensure_host()


if __name__ == "__main__":
    unittest.main()
