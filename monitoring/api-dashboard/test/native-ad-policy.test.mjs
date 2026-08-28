import assert from "node:assert/strict";
import test from "node:test";

import {
  isValidRepeatGapSeconds,
  repeatGapLabel,
} from "../src/lib/nativeAdPolicy.js";

test("AdMob repeat gap accepts zero or at least one minute", () => {
  assert.equal(isValidRepeatGapSeconds(0), true);
  assert.equal(isValidRepeatGapSeconds(59), false);
  assert.equal(isValidRepeatGapSeconds(60), true);
  assert.equal(isValidRepeatGapSeconds(2_592_000), true);
  assert.equal(isValidRepeatGapSeconds(2_592_001), false);
});

test("AdMob repeat gap labels zero as unlimited", () => {
  assert.equal(repeatGapLabel(0), "No repeat limit");
  assert.equal(repeatGapLabel(60), "1m");
  assert.equal(repeatGapLabel(61), "1m 1s");
  assert.equal(repeatGapLabel(5_400), "90m");
  assert.equal(repeatGapLabel(21_600), "6h");
});
