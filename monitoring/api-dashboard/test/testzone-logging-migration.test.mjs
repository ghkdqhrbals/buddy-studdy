import test from "node:test";
import assert from "node:assert/strict";
import { execFile } from "node:child_process";
import fs from "node:fs/promises";
import path from "node:path";
import { promisify } from "node:util";
import { fileURLToPath } from "node:url";

const execFileAsync = promisify(execFile);
const testDirectory = path.dirname(fileURLToPath(import.meta.url));
const repositoryRoot = path.resolve(testDirectory, "../../..");
const workflowPath = path.join(
  repositoryRoot,
  "docs/deploy-repo-template/migrate-testzone-component-logging.yml",
);
const helperPath = path.join(
  repositoryRoot,
  "docs/deploy-repo-template/scripts/migrate_testzone_component_logging.py",
);
const moduleDocsPath = path.join(
  repositoryRoot,
  "docs/deploy-repo-template/deployment-modules.md",
);
const readmePath = path.join(
  repositoryRoot,
  "docs/deploy-repo-template/README.md",
);

test("legacy TestZone logging migration defaults to read-only preflight", async () => {
  const workflow = await fs.readFile(workflowPath, "utf8");
  const triggerBlock = workflow.split("concurrency:", 1)[0];

  assert.match(triggerBlock, /^  workflow_dispatch:$/m);
  assert.match(triggerBlock, /^        default: false$/m);
  assert.match(triggerBlock, /^        type: boolean$/m);
  assert.doesNotMatch(triggerBlock, /repository_dispatch:|schedule:/);
  assert.match(workflow, /^  group: deploy-macbookair-testzone$/m);
  assert.match(workflow, /migrate_testzone_component_logging\.py preflight/);
  assert.match(workflow, /^        if: \$\{\{ inputs\.apply \}\}$/m);
  assert.match(workflow, /migrate_testzone_component_logging\.py migrate/);
  assert.match(
    workflow,
    /migration-\$\{\{ github\.run_id \}\}-\$\{\{ github\.run_attempt \}\}\.json/,
  );
  assert.doesNotMatch(workflow.split("    steps:", 1)[0], /runner\.temp/);
  assert.equal((workflow.match(/\$\{\{ runner\.temp \}\}/g) || []).length, 3);
  assert.match(workflow, /^        if: always\(\)$/m);
});

test("helper limits mutation to the two audited legacy containers", async () => {
  const helper = await fs.readFile(helperPath, "utf8");

  assert.equal((helper.match(/name="buddystudy-testzone-postgres"/g) || []).length, 1);
  assert.equal((helper.match(/name="buddystudy-testzone-redis"/g) || []).length, 1);
  assert.match(helper, /postgres:16-alpine/);
  assert.match(helper, /postgres:17-alpine/);
  assert.match(helper, /redis:7\.4-alpine/);
  assert.match(helper, /redis:8-alpine/);
  assert.match(helper, /volume_destination="\/var\/lib\/postgresql\/data"/);
  assert.match(helper, /volume_destination="\/data"/);
  assert.match(helper, /labels\.get\("testzone\.managed"\) != "true"/);
  assert.match(helper, /host_config\.get\("AutoRemove"\) is True/);
  assert.match(helper, /log_type not in \("json-file", "local"\)/);
  assert.match(helper, /sha256:\[0-9a-f\]\{64\}/);
  assert.match(helper, /runtime\.list_names\(backup_prefix\)/);
  assert.match(helper, /name=\^\/\{prefix\}/);
});

test("helper explicitly reuses the inspected anonymous volume and bounded logs", async () => {
  const helper = await fs.readFile(helperPath, "utf8");

  assert.match(helper, /"Source": volume_identity\["Name"\]/);
  assert.match(helper, /"Target": spec\.volume_destination/);
  assert.match(helper, /"ReadOnly": False/);
  assert.match(helper, /replacement_volume\["Name"\] != plan\.volume_name/);
  assert.match(helper, /"max-size": "10m"/);
  assert.match(helper, /"max-file": "3"/);
  assert.match(helper, /"compress": "true"/);
  assert.match(helper, /runtime\.stop\(plan\.spec\.name\)/);
  assert.match(helper, /"docker", "stop", "--time", "60"/);
  assert.match(helper, /runtime\.remove\(plan\.backup_name\)/);
  assert.match(
    helper,
    /if plan\.was_running:[\s\S]{0,180}runtime\.start\(plan\.spec\.name\)/,
  );
  assert.doesNotMatch(helper, /docker\s+(?:volume|system|image|builder)\s+(?:rm|prune)/);
  assert.doesNotMatch(helper, /curl|wget|pg_isready|redis-cli|\.State\.Health/);
});

test("helper compiles and summaries disclose downtime, retained data, and log loss", async () => {
  await execFileAsync("python3", ["-m", "py_compile", helperPath]);
  const [helper, readme, modules] = await Promise.all([
    fs.readFile(helperPath, "utf8"),
    fs.readFile(readmePath, "utf8"),
    fs.readFile(moduleDocsPath, "utf8"),
  ]);

  for (const text of [helper, readme, modules]) {
    assert.match(text, /brief (?:component )?restart/i);
    assert.match(text, /json-file/);
    assert.match(text, /cannot recover|unrecoverable/i);
    assert.match(text, /volume/i);
  }
  assert.match(helper, /no volume was copied, removed, or pruned/i);
  assert.match(readme, /apply=false/);
  assert.match(readme, /apply=true/);
  assert.match(modules, /no runtime health, HTTP, or\s+database check/i);
});
