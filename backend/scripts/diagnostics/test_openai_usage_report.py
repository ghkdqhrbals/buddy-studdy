import importlib.util
import json
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location("usage_report", Path(__file__).with_name("openai-usage-report.py"))
report = importlib.util.module_from_spec(spec)
spec.loader.exec_module(report)


def row(ref="a" * 32, **kwargs):
    return dict(event="openai_usage", schemaVersion=1, eventRef=ref, operation="realtime",
                stage="response", model="test-model", transport="realtime", granularity="provider_response",
                outcome="succeeded", usageAvailable=True, inputTokens=100, outputTokens=20,
                cachedInputTokens=70, **kwargs)


class UsageReportTest(unittest.TestCase):
    def test_replayed_logs_and_physical_retries_do_not_double_count_tokens(self):
        call = row()
        retry = dict(call, eventRef="b" * 32, granularity="physical_attempt", attempt=2, usageAvailable=False)
        result = report.summarize(map(json.dumps, [call, call, retry]))
        group = result["groups"][0]
        self.assertEqual(result["duplicateEvents"], 1)
        self.assertEqual(group["calls"], 1)
        self.assertEqual(group["retryAttempts"], 1)
        self.assertEqual(group["tokenTotals"]["inputTokens"], 100)
        self.assertEqual(group["tokenTotals"]["cachedInputTokens"], 70)

    def test_final_usage_replaces_unknown_disconnect_not_an_extra_call(self):
        unknown = dict(row(), usageAvailable=False, outcome="disconnected", inputTokens=None, outputTokens=None)
        result = report.summarize(map(json.dumps, [unknown, row()]))["groups"][0]
        self.assertEqual(result["calls"], 1)
        self.assertEqual(result["unknownUsageCalls"], 0)

    def test_direct_summary_retries_are_visible_without_sdk_transport_events(self):
        result = report.summarize([json.dumps(row(attempt=2))])["groups"][0]
        self.assertEqual(result["callsWithRetries"], 1)
        self.assertEqual(result["physicalAttempts"], 0)
        self.assertEqual(result["tokenTotals"]["inputTokens"], 100)

    def test_reattachment_retains_known_stage_and_observed_time_in_either_order(self):
        partial = dict(row(), usageAvailable=False, outcome="disconnected", stage="question-readback", durationMs=120)
        final = row()
        for values in ([partial, final], [final, partial]):
            result = report.summarize(map(json.dumps, values))["groups"][0]
            self.assertEqual(result["calls"], 1)
            self.assertEqual(result["stage"], "question-readback")
            self.assertEqual(result["averageDurationMs"], 120)
            self.assertEqual(result["outcomes"], {"succeeded": 1})

    def test_duration_only_usage_and_prediction_counts_are_not_discarded(self):
        duration = dict(row(), inputTokens=None, outputTokens=None, cachedInputTokens=None, audioSeconds=12.5)
        result = report.summarize(map(json.dumps, [duration]))["groups"][0]
        self.assertEqual(result["usageSamples"], 1)
        self.assertEqual(result["audioSeconds"], 12.5)
        self.assertIsNone(result["tokenTotals"]["inputTokens"])
        predictions = row(acceptedPredictionTokens=10, rejectedPredictionTokens=2)
        result = report.summarize(map(json.dumps, [predictions]))["groups"][0]
        self.assertEqual(result["tokenTotals"]["rejectedPredictionTokens"], 2)

    def test_unknown_or_unreported_fields_stay_unknown(self):
        value = dict(row(), usageAvailable=False)
        result = report.summarize([json.dumps(value)])["groups"][0]
        self.assertEqual(result["unknownUsageCalls"], 1)
        self.assertIsNone(result["tokenTotals"]["inputTokens"])
        self.assertIsNone(result["averageDurationMs"])

    def test_plain_and_structured_log_envelopes(self):
        plain = "2026 INFO logger openai_usage " + json.dumps(row())
        envelope = json.dumps({"message": "openai_usage " + json.dumps(row("b" * 32))})
        result = report.summarize([plain, envelope, "bad {", "null", "[]"])
        self.assertEqual(result["groups"][0]["calls"], 2)
        self.assertEqual(result["ignoredLines"], 3)

    def test_aggregates_do_not_expose_event_references_or_unknown_payloads(self):
        value = row()
        value["transcript"] = "private sample"
        result = json.dumps(report.summarize([json.dumps(value)]))
        self.assertNotIn("private sample", result)
        self.assertNotIn("a" * 32, result)


if __name__ == "__main__":
    unittest.main()
