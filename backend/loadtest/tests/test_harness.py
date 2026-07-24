import json
import tempfile
import unittest
from pathlib import Path

import sys

LOADTEST = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(LOADTEST))

from normalize_results import normalize_k6
from ngrinder.run_test import execution_shape, graph_points
from report_results import common_direction
from validate_scenarios import validate_manifest


class HarnessTests(unittest.TestCase):
    def test_manifest_has_expected_weighted_mix(self):
        manifest = validate_manifest(LOADTEST / "scenarios.json")
        self.assertNotIn("health", manifest["scenarios"])
        self.assertEqual(
            set(manifest["scenarios"]),
            {"public-questions", "studies", "mobile-read-mix"},
        )
        mix = manifest["scenarios"]["mobile-read-mix"]["requests"]
        self.assertEqual([request["weight"] for request in mix], [70, 30])
        self.assertEqual(sum(request["weight"] for request in mix), 100)

    def test_k6_normalization_and_generator_validity(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            for child in ("raw", "telemetry", "generator-telemetry"):
                (root / child).mkdir()
            raw = root / "raw" / "mvc-round1-studies-rps1000.json"
            raw.write_text(
                json.dumps(
                    {
                        "metrics": {
                            "http_reqs": {"values": {"rate": 990}},
                            "http_req_failed": {"values": {"rate": 0}},
                            "response_validation_failed": {"values": {"rate": 0}},
                            "dropped_iterations": {"values": {"count": 0}},
                            "http_req_duration": {
                                "values": {
                                    "med": 1,
                                    "p(90)": 2,
                                    "p(95)": 3,
                                    "p(99)": 5,
                                }
                            },
                        }
                    }
                )
            )
            (root / "telemetry" / "k6-mvc-round1-studies-rps1000.jsonl").write_text(
                json.dumps(
                    {
                        "process": {"cpu_percent": 50, "rss_bytes": 100, "os_threads": 20},
                        "actuator": {"jvm.heap.used": 50},
                        "postgres": {"connections_waiting": 0, "blks_hit": 10, "blks_read": 0},
                        "redis": {},
                        "containers": {},
                    }
                )
                + "\n"
            )
            (
                root
                / "generator-telemetry"
                / "mvc-round1-studies-rps1000.jsonl"
            ).write_text(
                json.dumps(
                    {
                        "process": {"cpuPercent": 75, "rssBytes": 100},
                        "hostCpu": {"normalizedPercent": 60},
                        "hostMemory": {"totalBytes": 1000, "availableBytes": 500},
                        "network": {
                            "receivedBytes": 100,
                            "sentBytes": 100,
                            "receiveErrors": 0,
                            "transmitErrors": 0,
                            "receiveDrops": 0,
                            "transmitDrops": 0,
                        },
                    }
                )
                + "\n"
            )
            match = __import__("normalize_results").K6_PATTERN.match(raw.name)
            result = normalize_k6(raw, match, root)
            self.assertTrue(result["validity"]["valid"])
            self.assertTrue(result["classification"]["sustainable"])
            self.assertEqual(result["summary"]["successRps"], 990)

    def test_generator_network_fault_invalidates_run(self):
        from normalize_results import validity

        result = validity(
            {
                "hostCpuP95": 20,
                "memoryUsedPercentPeak": 40,
                "networkReceiveDrops": 1,
            },
            failure_rate=0,
            dropped=0,
        )
        self.assertFalse(result["valid"])

    def test_final_direction_requires_both_tools(self):
        runs = []
        for tool in ("k6", "ngrinder"):
            for round_number in range(1, 4):
                for runtime, rps in (("mvc", 100), ("webflux", 120)):
                    runs.append(
                        {
                            "tool": tool,
                            "runtime": runtime,
                            "round": round_number,
                            "scenario": "studies",
                            "load": {
                                "type": "rps" if tool == "k6" else "vusers",
                                "value": 1,
                            },
                            "summary": {"successRps": rps, "failureRate": 0},
                            "resources": {},
                            "generator": {},
                            "validity": {"valid": True},
                            "classification": {"saturated": False},
                        }
                    )
        self.assertIn("WebFlux/R2DBC", common_direction(runs))

    def test_ngrinder_nested_graph_is_normalized(self):
        points = graph_points(
            {
                "TPS": {"TPS": [10.0, 12.0]},
                "Tests": {"Tests": [10, 22]},
                "Errors": {"Errors": [0, 1]},
                "Mean_Test_Time_(ms)": {"Mean_Test_Time_(ms)": [3.5, 4.0]},
                "chartInterval": 1,
            }
        )
        self.assertEqual(points[1]["rps"], 12.0)
        self.assertEqual(points[1]["requests"], 22.0)
        self.assertEqual(points[1]["errors"], 1.0)
        self.assertEqual(points[1]["meanMs"], 4.0)

    def test_ngrinder_splits_one_thousand_vusers_without_oversubscription(self):
        self.assertEqual(execution_shape(1000, 4, 250), (4, 250))
        self.assertEqual(execution_shape(800, 4, 250), (4, 200))
        self.assertEqual(execution_shape(200, 4, 250), (1, 200))

    def test_ngrinder_rejects_an_unrepresentable_execution_shape(self):
        with self.assertRaisesRegex(ValueError, "cannot be split exactly"):
            execution_shape(997, 4, 250)


if __name__ == "__main__":
    unittest.main()
