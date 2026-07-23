#!/usr/bin/env python3
import argparse
import json
from pathlib import Path
from urllib.parse import urlsplit


ALLOWED_METHODS = {"GET"}
REQUIRED_REQUEST_KEYS = {
    "name",
    "method",
    "path",
    "weight",
    "authenticated",
    "expectedStatus",
    "requiredJsonPaths",
}


def validate_manifest(path: Path) -> dict:
    data = json.loads(path.read_text())
    if data.get("schemaVersion") != 1:
        raise ValueError("scenarios.json schemaVersion must be 1")
    scenarios = data.get("scenarios")
    if not isinstance(scenarios, dict) or not scenarios:
        raise ValueError("scenarios must be a non-empty object")

    for scenario_name, scenario in scenarios.items():
        if not scenario_name or not isinstance(scenario, dict):
            raise ValueError("scenario names and definitions must be non-empty")
        requests = scenario.get("requests")
        if not isinstance(requests, list) or not requests:
            raise ValueError(f"{scenario_name}: requests must be a non-empty array")
        total_weight = 0
        request_names = set()
        for request in requests:
            missing = REQUIRED_REQUEST_KEYS - request.keys()
            if missing:
                raise ValueError(f"{scenario_name}: request is missing {sorted(missing)}")
            name = request["name"]
            if name in request_names:
                raise ValueError(f"{scenario_name}: duplicate request name {name}")
            request_names.add(name)
            if request["method"] not in ALLOWED_METHODS:
                raise ValueError(f"{scenario_name}/{name}: unsupported method {request['method']}")
            parsed = urlsplit(request["path"].replace("${STUDIES_LIMIT}", "100"))
            if not parsed.path.startswith("/") or parsed.scheme or parsed.netloc:
                raise ValueError(f"{scenario_name}/{name}: path must be an absolute URL path")
            if not isinstance(request["authenticated"], bool):
                raise ValueError(f"{scenario_name}/{name}: authenticated must be boolean")
            if not isinstance(request["expectedStatus"], int):
                raise ValueError(f"{scenario_name}/{name}: expectedStatus must be an integer")
            weight = request["weight"]
            if not isinstance(weight, int) or weight <= 0:
                raise ValueError(f"{scenario_name}/{name}: weight must be a positive integer")
            total_weight += weight
            for key in ("requiredJsonPaths", "nonEmptyJsonPaths"):
                values = request.get(key, [])
                if not isinstance(values, list) or not all(isinstance(value, str) and value for value in values):
                    raise ValueError(f"{scenario_name}/{name}: {key} must contain non-empty strings")
        if total_weight != 100:
            raise ValueError(f"{scenario_name}: request weights must total 100, got {total_weight}")
    return data


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("manifest", type=Path, nargs="?", default=Path(__file__).with_name("scenarios.json"))
    parser.add_argument("--only", help="Comma-separated scenario names that must exist")
    args = parser.parse_args()
    manifest = validate_manifest(args.manifest)
    selected = [value for value in (args.only or "").split(",") if value]
    missing = sorted(set(selected) - manifest["scenarios"].keys())
    if missing:
        raise SystemExit(f"unknown scenarios: {', '.join(missing)}")
    print(f"validated {len(manifest['scenarios'])} load-test scenarios")


if __name__ == "__main__":
    main()
