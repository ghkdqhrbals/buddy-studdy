#!/usr/bin/env python3
"""Summarize privacy-safe openai_usage logs; no network, credentials, or content input needed."""
import argparse
import json
import sys
from collections import defaultdict

TOKEN_FIELDS = (
    "inputTokens", "outputTokens", "totalTokens", "cachedInputTokens",
    "inputTextTokens", "inputAudioTokens", "inputImageTokens",
    "outputTextTokens", "outputAudioTokens", "outputImageTokens",
    "reasoningTokens", "cachedTextTokens", "cachedAudioTokens", "cachedImageTokens",
    "acceptedPredictionTokens", "rejectedPredictionTokens",
)


def parse_line(line):
    """Accept plain application logs or a JSON logging envelope's message field."""
    try:
        envelope = json.loads(line)
        if isinstance(envelope, dict):
            if envelope.get("event") == "openai_usage":
                return envelope
            line = envelope.get("message", "")
    except (ValueError, TypeError):
        pass
    if not isinstance(line, str) or "openai_usage " not in line:
        return None
    try:
        value = json.loads(line.split("openai_usage ", 1)[1])
    except (ValueError, TypeError):
        return None
    return value if isinstance(value, dict) and value.get("event") == "openai_usage" else None


def number(value):
    return value if isinstance(value, (int, float)) and not isinstance(value, bool) and value >= 0 else None


def summarize(lines):
    unique = {}
    ignored = duplicates = 0
    for line in lines:
        row = parse_line(line)
        if row is None or row.get("schemaVersion") != 1:
            ignored += 1
            continue
        ref = row.get("eventRef")
        if not isinstance(ref, str) or not 16 <= len(ref) <= 64 or any(c not in "0123456789abcdef" for c in ref):
            ignored += 1
            continue
        if ref in unique:
            duplicates += 1
            # A reattached sideband may supply the provider's final usage after a
            # disconnect had reported an unknown bill for this same response.
            previous = unique[ref]
            primary, secondary = (row, previous) if row.get("usageAvailable") is True and previous.get("usageAvailable") is not True else (previous, row)
            combined = dict(primary)
            if combined.get("stage") == "response" and secondary.get("stage") not in (None, "response"):
                combined["stage"] = secondary["stage"]
            if number(combined.get("durationMs")) is None and number(secondary.get("durationMs")) is not None:
                combined["durationMs"] = secondary["durationMs"]
            unique[ref] = combined
        else:
            unique[ref] = row

    groups = defaultdict(list)
    for row in unique.values():
        groups[tuple(row.get(key) for key in ("operation", "stage", "model", "transport"))].append(row)
    output = []
    for key, rows in sorted(groups.items(), key=lambda item: str(item[0])):
        calls = [row for row in rows if row.get("granularity") != "physical_attempt"]
        attempts = [row for row in rows if row.get("granularity") == "physical_attempt"]
        measured = [row for row in calls if row.get("usageAvailable") is True]
        token_totals = {}
        samples = {}
        for field in TOKEN_FIELDS:
            values = [number(row.get(field)) for row in measured]
            values = [value for value in values if value is not None]
            token_totals[field] = sum(values) if values else None
            samples[field] = len(values)
        outcomes = defaultdict(int)
        for row in calls:
            outcomes[row.get("outcome", "unknown")] += 1
        durations = [number(row.get("durationMs")) for row in calls]
        durations = [value for value in durations if value is not None]
        seconds = [number(row.get("audioSeconds")) for row in measured]
        seconds = [value for value in seconds if value is not None]
        output.append(dict(zip(("operation", "stage", "model", "transport"), key),
            calls=len(calls), physicalAttempts=len(attempts),
            retryAttempts=sum(1 for row in attempts if (number(row.get("attempt")) or 0) > 1),
            callsWithRetries=sum(1 for row in calls if (number(row.get("attempt")) or 0) > 1),
            usageSamples=len(measured), unknownUsageCalls=len(calls) - len(measured),
            outcomes=dict(outcomes), tokenTotals=token_totals, tokenSamples=samples,
            audioSeconds=sum(seconds) if seconds else None, audioSecondsSamples=len(seconds),
            averageDurationMs=round(sum(durations) / len(durations), 1) if durations else None,
        ))
    return {"schemaVersion": 1, "duplicateEvents": duplicates, "ignoredLines": ignored,
            "groups": output,
            "notes": ["Cached and modality tokens are subsets; do not add them to input/output totals.",
                      "Unknown usage is not zero. Token totals include only reported samples.",
                      "Physical SDK attempts are separate from logical calls and carry no extra summed tokens.",
                      "This is measured token usage, not a provider invoice or a currency estimate."]}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("logs", nargs="*", help="Log paths; omit to read stdin")
    parser.add_argument("--json", action="store_true", help="Emit aggregate JSON without event IDs")
    args = parser.parse_args()

    def lines():
        if not args.logs:
            yield from sys.stdin
        for path in args.logs:
            with open(path, encoding="utf-8", errors="replace") as source:
                yield from source

    report = summarize(lines())
    if args.json:
        print(json.dumps(report, indent=2, ensure_ascii=False))
    else:
        print("operation / stage / model | calls | physical retries / retried calls | unknown usage | input / output / cached | reported audio seconds")
        for group in report["groups"]:
            tokens = group["tokenTotals"]
            display = lambda value: "?" if value is None else str(value)
            print(f'{group["operation"]} / {group["stage"]} / {group["model"]} | '
                  f'{group["calls"]} | {group["retryAttempts"]} / {group["callsWithRetries"]} | {group["unknownUsageCalls"]} | '
                  f'{display(tokens["inputTokens"])} / {display(tokens["outputTokens"])} / '
                  f'{display(tokens["cachedInputTokens"])} | {display(group["audioSeconds"])}')
        print(f'Duplicate events ignored: {report["duplicateEvents"]}')
        for note in report["notes"]:
            print(note)


if __name__ == "__main__":
    main()
