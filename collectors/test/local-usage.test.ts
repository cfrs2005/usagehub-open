import assert from "node:assert/strict";
import { mkdtempSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import test from "node:test";
import { LocalUsageCollector, runLocalCommand, toTokenUsagePayload } from "../src/local-usage/collector.js";

test("reads an explicitly configured usage JSON source and caches by TTL", async () => {
  const stateDir = mkdtempSync(join(tmpdir(), "usagehub-local-"));
  let calls = 0;
  let now = Date.parse("2026-09-03T06:30:00.000Z");
  const collector = new LocalUsageCollector({
    stateDir,
    now: () => now,
    ttlMs: 60_000,
    sourceFiles: { codex: "/tmp/usagehub-codex.json" },
    sourceReader: () => {
      calls += 1;
      return {
        modifiedAtMs: Date.parse("2026-09-03T06:00:00.000Z"),
        text: JSON.stringify({
          overview: {
            total_tokens: 300,
            total_usd: "4.500001",
            heatmap: [
              { date: "2026-09-02", value: 120, total_usd: "1.250001", source_cost_usd: "0.10" },
              { date: "2026-09-03", value: 180, total_usd: "3.25", source_cost_usd: "0.20" },
            ],
          },
        }),
      };
    },
  });
  try {
    const first = await collector.collect("codex");
    assert.equal(first.source, "ccusage_codex");
    assert.equal(first.quota_semantics, "not_official_remaining_quota");
    assert.equal(first.today_tokens, 120);
    assert.equal(first.today_cost_usd, 1.250001);
    assert.equal(first.total_tokens, 300);
    assert.equal(first.total_cost_usd, 4.500001);
    assert.equal(JSON.stringify(first).includes("source_cost_usd"), false);
    assert.deepEqual(toTokenUsagePayload(first, "install-token-01"), {
      installation_id: "install-token-01",
      observed_at: 1_788_415_200,
      today_tokens: 120,
      total_tokens: 300,
      today_cost_usd: 1.250001,
      total_cost_usd: 4.500001,
      cost_label: "标准 API 等价费用",
      source: "ccusage_codex",
    });
    now += 30_000;
    assert.equal((await collector.collect("codex")).cached, true);
    assert.equal(calls, 1);
  } finally {
    rmSync(stateDir, { recursive: true, force: true });
  }
});

test("ccusage command timeout is bounded", async () => {
  const started = Date.now();
  await assert.rejects(runLocalCommand(process.execPath, ["-e", "setTimeout(() => {}, 10000)"], 50));
  assert.ok(Date.now() - started < 1_000);
});

test("missing configured JSON falls back to sanitized CLI data and isolates providers", async () => {
  const stateDir = mkdtempSync(join(tmpdir(), "usagehub-local-isolation-"));
  const collector = new LocalUsageCollector({
    stateDir,
    now: () => Date.parse("2026-09-02T18:00:00.000Z"),
    sourceFiles: { claude: "/tmp/not-present.json", codex: "/tmp/not-present.json" },
    sourceReader: () => { throw new Error("missing file"); },
    runner: async (command) => {
      if (command === "ccusage") throw new Error("simulated Claude history failure");
      return JSON.stringify({
        daily: [{ date: "Sep 02, 2026", totalTokens: 42, costUSD: 0.5, cwd: "/private/work" }],
        account: "private@example.test",
      });
    },
  });
  try {
    const [claude, codex] = await Promise.all([collector.collect("claude"), collector.collect("codex")]);
    assert.equal(claude.status, "unavailable");
    assert.equal(codex.status, "ok");
    assert.equal(codex.total_tokens, 42);
    assert.equal(JSON.stringify(codex).includes("/private/work"), false);
    assert.equal(JSON.stringify(codex).includes("private@example.test"), false);
  } finally {
    rmSync(stateDir, { recursive: true, force: true });
  }
});
