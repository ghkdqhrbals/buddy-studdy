import test from "node:test";
import assert from "node:assert/strict";
import fs from "node:fs/promises";
import path from "node:path";
import { fileURLToPath } from "node:url";

const testDirectory = path.dirname(fileURLToPath(import.meta.url));
const composePath = path.resolve(testDirectory, "../../docker-compose.yml");
const deployTemplatePath = path.resolve(
  testDirectory,
  "../../../docs/deploy-repo-template/deploy-macbookair-monitoring.yml",
);

test("Grafana Live accepts only the public Grafana origin", async () => {
  const [compose, deployTemplate] = await Promise.all([
    fs.readFile(composePath, "utf8"),
    fs.readFile(deployTemplatePath, "utf8"),
  ]);

  for (const source of [compose, deployTemplate]) {
    assert.match(
      source,
      /GF_LIVE_ALLOWED_ORIGINS(?::|=) ?https:\/\/grafana\.lowfidev\.cloud/,
    );
  }
});
