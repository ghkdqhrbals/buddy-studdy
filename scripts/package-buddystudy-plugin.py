#!/usr/bin/env python3
"""Build the public author upload without private bindings or reviewer secrets.

These local package checks do not replace portal validation or live review cases.
Only the manifest, remote MCP configuration and referenced icons are included.
"""

import argparse
import hashlib
import json
from pathlib import Path
import re
import struct
from urllib.parse import urlsplit
from zipfile import ZipFile, ZIP_DEFLATED


ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "plugins" / "buddystudy"
CATEGORIES = {
    "Business & Operations", "Communication", "Creativity", "Data & Analytics",
    "Developer Tools", "Education & Research", "Entertainment", "Finance",
    "Healthcare", "Other", "Productivity", "Scientific Research", "Security", "Travel",
}


def require(condition, message):
    if not condition:
        raise ValueError(message)


def https_url(value):
    parsed = urlsplit(value)
    return parsed.scheme == "https" and bool(parsed.hostname) and not parsed.username and not parsed.password


def build(output):
    manifest = json.loads((SOURCE / "plugin.json").read_text())
    mcp = json.loads((SOURCE / "mcp.json").read_text())
    vendor = manifest["extensions"]["com.openai"]
    interface = vendor["interface"]
    require(manifest["name"] == "buddystudy", "Preserve the public plugin identity")
    require(re.fullmatch(r"\d+\.\d+\.\d+", manifest["version"]), "A semantic version is required")
    for container in (manifest, vendor):
        require(container.get("apps") is None, "Public uploads cannot declare private app bindings")
        require(container.get("hooks") is None, "Public uploads cannot include lifecycle hooks")
    require(interface["category"] in CATEGORIES, "Use a supported directory category")
    for field, maximum in (("displayName", 30), ("shortDescription", 30), ("longDescription", 4000), ("developerName", 80)):
        require(0 < len(interface[field]) <= maximum, f"Invalid listing field: {field}")
    require(interface["developerName"] == manifest["author"]["name"], "Publisher names differ")
    for field in ("websiteURL", "supportURL", "privacyPolicyURL", "termsOfServiceURL"):
        require(https_url(interface[field]) and len(interface[field]) <= 1024, f"Invalid listing URL: {field}")
    prompts = interface["defaultPrompt"]
    if isinstance(prompts, str):
        prompts = [prompts]
    require(1 <= len(prompts) <= 3, "Supply one to three starter prompts")
    require(all(p.strip() and len(p) <= 128 and "\n" not in p and "@" not in p for p in prompts), "Invalid starter prompt")
    require(len({" ".join(p.split()).casefold() for p in prompts}) == len(prompts), "Duplicate starter prompts")
    cases = vendor["review"]["test_cases"]
    require(len(cases["positive"]) == 5 and len(cases["negative"]) == 3, "Initial review needs five positive and three negative cases")
    for kind, fields in (("positive", ("description", "prompt", "tools_triggered", "expected_behavior")), ("negative", ("description", "prompt", "expected_behavior"))):
        for case in cases[kind]:
            require(all(isinstance(case.get(f), str) and case[f].strip() for f in fields), f"Incomplete {kind} case")
    require(https_url(vendor["review"]["demo_recording_url"]), "A recording URL is required")
    require(vendor["publication"]["release_notes"].strip(), "Release notes are required")
    require(len(mcp["mcpServers"]) == 1, "This package has one concrete MCP server")
    server = mcp["mcpServers"]["buddystudy"]
    require(server["type"] == "streamable-http" and server["url"] == "https://api.ghkdqhrbals.org/api/v1/mcp", "Preserve the hosted MCP endpoint")
    auth = server["extensions"]["com.openai"]["auth"]
    require(auth["type"] == "oauth" and auth["client"]["clientId"] == "buddystudy-chatgpt", "Preserve the predefined OAuth client")
    require(set(auth["baseScopes"]) == {"learning:read", "learning:write", "learning:delete", "profile:read", "offline_access"}, "OAuth scopes differ from the public contract")
    files = {"plugin.json", "mcp.json"}
    for field, minimum in (("logo", 256), ("composerIcon", 48), ("logoDark", 256), ("composerIconDark", 48)):
        if field not in interface:
            continue
        relative = interface[field]
        path = SOURCE / relative
        require(relative.startswith("./assets/") and path.resolve().is_relative_to(SOURCE.resolve()), f"Invalid icon path: {field}")
        require(not path.is_symlink(), "Symlinks are not allowed")
        data = path.read_bytes()
        require(data[:8] == b"\x89PNG\r\n\x1a\n" and data[12:16] == b"IHDR", "Icons must be PNG files")
        width, height = struct.unpack(">II", data[16:24])
        require(minimum <= width == height <= 4096 and len(data) <= 5 * 1024 * 1024, f"Invalid icon size: {field}")
        files.add(path.relative_to(SOURCE).as_posix())
    output.parent.mkdir(parents=True, exist_ok=True)
    with ZipFile(output, "w", compression=ZIP_DEFLATED) as archive:
        for name in sorted(files):
            archive.write(SOURCE / name, name)
    with ZipFile(output) as archive:
        require(archive.testzip() is None and set(archive.namelist()) == files, "ZIP inventory failed")
        require(json.loads(archive.read("plugin.json")) == manifest, "ZIP manifest differs from source")
        require(json.loads(archive.read("mcp.json")) == mcp, "ZIP MCP configuration differs from source")
    print(f"Candidate archive: {output}")
    print(f"Files: {', '.join(sorted(files))}")
    print(f"SHA-256: {hashlib.sha256(output.read_bytes()).hexdigest()}")
    print("Local package checks passed. Live review evidence and portal validation are separate requirements.")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    version = json.loads((SOURCE / "plugin.json").read_text())["version"]
    build(args.output or ROOT / "dist" / f"buddystudy-public-{version}-candidate.zip")
