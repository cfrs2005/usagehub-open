import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import test from "node:test";
import {
  PLATFORM_ENDPOINTS,
  classifyWindow,
  normalizeClaude,
  normalizeCodex,
  normalizeTokenUsage,
  validateClaudeIngest,
  validateCodexIngest,
  validateTokenUsageIngest,
} from "../src/index.js";

test("exports the public platform credential and route contract", () => {
  assert.equal(PLATFORM_ENDPOINTS.ingest.length, 4);
  assert.ok(PLATFORM_ENDPOINTS.credentials.includes("/v1/collectors"));
});

function fixture(name: string): unknown {
  return JSON.parse(readFileSync(new URL(`../../fixtures/${name}`, import.meta.url), "utf8"));
}

test("validates and normalizes normal Claude data", () => {
  const result = validateClaudeIngest(fixture("claude-normal.json"));
  assert.equal(result.valid, true, result.errors.join("\n"));
  const normalized = normalizeClaude(result.value!);
  assert.deepEqual(normalized.windows.map((window) => [window.limitId, window.kind]), [
    ["five_hour", "short"],
    ["seven_day", "weekly"],
  ]);
});

test("handles a missing Claude rate_limits sample without inventing data", () => {
  const result = validateClaudeIngest(fixture("claude-missing.json"));
  assert.equal(result.valid, true, result.errors.join("\n"));
  assert.deepEqual(normalizeClaude(result.value!).windows, []);
});

test("normalizes Codex multi-bucket, null, unordered, and future limit data", () => {
  const result = validateCodexIngest(fixture("codex-multi-bucket.json"));
  assert.equal(result.valid, true, result.errors.join("\n"));
  const windows = normalizeCodex(result.value!).windows;
  assert.equal(windows.length, 4);
  assert.deepEqual(windows.map((window) => window.limitId), [
    "codex:primary",
    "codex:secondary",
    "future-limit:primary",
    "other:primary",
  ]);
  assert.equal(windows[2]?.usedPercent, null);
});

test("accepts official overage percentages and rejects invalid values", () => {
  const input = fixture("claude-normal.json") as Record<string, any>;
  input.rate_limits.five_hour.used_percentage = 0;
  input.rate_limits.seven_day.used_percentage = 100;
  assert.equal(validateClaudeIngest(input).valid, true);
  input.rate_limits.five_hour.used_percentage = 101;
  assert.equal(validateClaudeIngest(input).valid, true);
  input.rate_limits.five_hour.used_percentage = -0.1;
  assert.equal(validateClaudeIngest(input).valid, false);
  input.rate_limits.five_hour.used_percentage = 10_001;
  assert.equal(validateClaudeIngest(input).valid, false);
});

test("accepts expired reset timestamps and preserves official values", () => {
  const input = fixture("codex-multi-bucket.json") as Record<string, any>;
  input.rate_limits.rateLimitsByLimitId.codex.primary.resetsAt = 1;
  const result = validateCodexIngest(input);
  assert.equal(result.valid, true, result.errors.join("\n"));
  assert.equal(normalizeCodex(result.value!).windows[0]?.resetsAt, 1);
});

test("rejects unknown fields at every contract boundary", () => {
  const claude = fixture("claude-normal.json") as Record<string, any>;
  claude.prompt = "forbidden";
  assert.match(validateClaudeIngest(claude).errors.join(" "), /unknown field/);

  const codex = fixture("codex-multi-bucket.json") as Record<string, any>;
  codex.rate_limits.rateLimitsByLimitId.codex.primary.account = "forbidden";
  assert.match(validateCodexIngest(codex).errors.join(" "), /unknown field/);
});

test("classifies short, weekly, and other windows by duration", () => {
  assert.equal(classifyWindow(15), "short");
  assert.equal(classifyWindow(10080), "weekly");
  assert.equal(classifyWindow(2880), "other");
});

test("validates token usage with a provider-specific source and strict allowlist", () => {
  const input = {
    installation_id: "token-install-01",
    observed_at: 1_788_374_970,
    today_tokens: 120,
    total_tokens: 300,
    today_cost_usd: 1.25,
    total_cost_usd: 4.5,
    cost_label: "标准 API 等价费用",
    source: "ccusage_codex",
  };
  const result = validateTokenUsageIngest("codex", input);
  assert.equal(result.valid, true, result.errors.join("\n"));
  assert.deepEqual(normalizeTokenUsage("codex", result.value!), {
    provider: "codex",
    installationId: "token-install-01",
    observedAt: 1_788_374_970,
    todayTokens: 120,
    totalTokens: 300,
    todayCostUsd: 1.25,
    totalCostUsd: 4.5,
    costLabel: "标准 API 等价费用",
    source: "ccusage_codex",
  });
  assert.equal(validateTokenUsageIngest("claude", input).valid, false);
  assert.equal(validateTokenUsageIngest("codex", { ...input, source_cost_usd: 2 }).valid, false);
  assert.equal(validateTokenUsageIngest("codex", { ...input, today_tokens: 301 }).valid, false);
});
