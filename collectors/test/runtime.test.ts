import assert from "node:assert/strict";
import { mkdtempSync, readFileSync, rmSync, statSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import test from "node:test";
import { LocalUsageCollector } from "../src/local-usage/collector.js";
import { runChannel, sanitizeError } from "../src/runtime/channels.js";
import { CHANNELS, readHealth, type Channel } from "../src/runtime/health.js";
import { nextDelayMs, runScheduler } from "../src/runtime/scheduler.js";
import type { CollectorConfig } from "../src/shared/config.js";
import { listPending, readRecord } from "../src/shared/queue.js";
import { DEFAULT_SETTINGS, loadSettings } from "../src/shared/settings.js";

function tempDir(prefix: string): string {
  return mkdtempSync(join(tmpdir(), prefix));
}

function testConfig(stateDir: string): CollectorConfig {
  return { apiUrl: "https://hub.example.test", ingestToken: "test-token-0123456789", stateDir, timeoutMs: 1_000, maxQueueItems: 64 };
}

test("settings: missing file writes an owner-only default template", () => {
  const dir = tempDir("usagehub-settings-");
  try {
    const path = join(dir, "nested", "collector.json");
    const loaded = loadSettings({ USAGEHUB_CONFIG: path });
    assert.equal(loaded.createdTemplate, true);
    assert.deepEqual(loaded.settings, DEFAULT_SETTINGS);
    assert.deepEqual(loaded.warnings, []);
    assert.deepEqual(JSON.parse(readFileSync(path, "utf8")), {
      claudeQuotaMinutes: 30, codexQuotaMinutes: 30, tokenUsageMinutes: 30, timezone: "Asia/Shanghai",
    });
    assert.equal(statSync(path).mode & 0o777, 0o600);
    assert.equal(loadSettings({ USAGEHUB_CONFIG: path }).createdTemplate, false);
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});

test("settings: invalid values fall back per field with warnings", () => {
  const dir = tempDir("usagehub-settings-");
  try {
    const path = join(dir, "collector.json");
    writeFileSync(path, JSON.stringify({ claudeQuotaMinutes: 0, codexQuotaMinutes: 1441, tokenUsageMinutes: 2.5, timezone: "Mars/Base" }));
    const loaded = loadSettings({ USAGEHUB_CONFIG: path });
    assert.deepEqual(loaded.settings, DEFAULT_SETTINGS);
    assert.equal(loaded.warnings.length, 4);
    writeFileSync(path, JSON.stringify({ claudeQuotaMinutes: 1, codexQuotaMinutes: 1440, tokenUsageMinutes: 45, timezone: "UTC" }));
    assert.deepEqual(loadSettings({ USAGEHUB_CONFIG: path }).settings, { claudeQuotaMinutes: 1, codexQuotaMinutes: 1440, tokenUsageMinutes: 45, timezone: "UTC" });
    writeFileSync(path, "{not json");
    const broken = loadSettings({ USAGEHUB_CONFIG: path });
    assert.deepEqual(broken.settings, DEFAULT_SETTINGS);
    assert.equal(broken.warnings.length, 1);
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});

test("settings: environment > file > default", () => {
  const dir = tempDir("usagehub-settings-");
  try {
    const path = join(dir, "collector.json");
    writeFileSync(path, JSON.stringify({ claudeQuotaMinutes: 20, timezone: "UTC" }));
    const env = { USAGEHUB_CONFIG: path, USAGEHUB_CLAUDE_USAGE_INTERVAL_MS: "600000", USAGEHUB_TIMEZONE: "Europe/Berlin" };
    const loaded = loadSettings(env);
    assert.equal(loaded.settings.claudeQuotaMinutes, 10);
    assert.equal(loaded.settings.timezone, "Europe/Berlin");
    assert.equal(loaded.settings.codexQuotaMinutes, 30);
    const bad = loadSettings({ ...env, USAGEHUB_CLAUDE_USAGE_INTERVAL_MS: "1000", USAGEHUB_TIMEZONE: "Nope/Zone" });
    assert.equal(bad.settings.claudeQuotaMinutes, 20);
    assert.equal(bad.settings.timezone, "UTC");
    assert.equal(bad.warnings.length, 2);
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});

test("channels are independent: a Codex failure does not block Claude quota or token usage", async () => {
  const stateDir = tempDir("usagehub-channels-");
  try {
    const config = testConfig(stateDir);
    const localCollector = new LocalUsageCollector({
      stateDir,
      now: () => Date.parse("2026-10-24T18:00:00Z"),
      sourceFiles: {},
      runner: async (command) => JSON.stringify(command === "ccusage"
        ? { daily: [{ period: "2026-10-25", agents: [{ agent: "claude", totalTokens: 10, totalCost: 0.5 }] }] }
        : { daily: [{ date: "2026-10-25", totalTokens: 7, costUSD: 0.25 }] }),
    });
    const deps = {
      config,
      settings: { ...DEFAULT_SETTINGS },
      installationId: "install-runtime-01",
      localCollector,
      lockRetries: 0,
      upload: async () => ({ sent: 0, pending: listPending(stateDir).length, error: "hub returned HTTP 503" }),
      codexRead: async () => { throw new Error("Codex app-server exited before responding"); },
      claudeProbe: async () => ({
        installation_id: "install-runtime-01",
        observed_at: 1_793_000_000,
        rate_limits: { five_hour: { used_percentage: 65, resets_at: 1_793_010_000 }, seven_day: { used_percentage: 50, resets_at: 1_793_100_000 } },
      }),
    };
    const results = new Map<Channel, Awaited<ReturnType<typeof runChannel>>>();
    for (const channel of CHANNELS) results.set(channel, await runChannel(channel, deps));

    assert.equal(results.get("codex_quota")!.outcome.error, "Codex app-server exited before responding");
    const kinds = listPending(stateDir).map((path) => { const r = readRecord(path); return `${r.provider}:${r.kind}`; }).sort();
    assert.deepEqual(kinds, ["claude:quota", "claude:token_usage", "codex:token_usage"]);
    assert.equal(results.get("claude_token_usage")!.summary.startsWith("today(2026-10-25 Asia/Shanghai) 10 tokens"), true);

    const health = readHealth(stateDir);
    assert.equal(health.channels.codex_quota?.lastError, "Codex app-server exited before responding");
    assert.equal(health.channels.claude_quota?.observedAt, new Date(1_793_000_000_000).toISOString());
    assert.match(health.channels.claude_quota?.lastError ?? "", /upload pending: hub returned HTTP 503/);
    assert.equal(health.pending, 3);
  } finally {
    rmSync(stateDir, { recursive: true, force: true });
  }
});

test("delivered records clear lastError and set uploadedAt", async () => {
  const stateDir = tempDir("usagehub-channels-ok-");
  try {
    const { removeRecord } = await import("../src/shared/queue.js");
    const deps = {
      config: testConfig(stateDir),
      settings: { ...DEFAULT_SETTINGS },
      installationId: "install-runtime-02",
      now: () => Date.parse("2026-10-25T04:10:00Z"),
      upload: async () => { for (const path of listPending(stateDir)) removeRecord(path); return { sent: 1, pending: 0 }; },
      codexRead: async () => ({ installation_id: "install-runtime-02", observed_at: 1_793_000_000, rate_limits: { rateLimitsByLimitId: { codex: { primary: { usedPercent: 1, windowDurationMins: 300, resetsAt: 1 } } } } }),
    };
    await runChannel("codex_quota", { ...deps, codexRead: async () => { throw new Error("boom"); } });
    const ok = await runChannel("codex_quota", deps);
    assert.equal(ok.outcome.error, undefined);
    assert.equal(ok.outcome.uploadedAt, "2026-10-25T04:10:00.000Z");
    const health = readHealth(stateDir).channels.codex_quota!;
    assert.equal(health.lastError, undefined);
    assert.equal(health.uploadedAt, "2026-10-25T04:10:00.000Z");
  } finally {
    rmSync(stateDir, { recursive: true, force: true });
  }
});

test("error text is single-line, bounded and token-free", () => {
  assert.equal(sanitizeError(new Error("bad\nthing  here")), "bad thing here");
  assert.equal(sanitizeError(new Error("Authorization: Bearer abcdefghijklmnopqrstuvwxyz")).includes("abcdefghijklmnop"), false);
  assert.equal(sanitizeError("uh_ingest_ABCDEF0123456789").includes("ABCDEF"), false);
  assert.equal(sanitizeError("x".repeat(1_000)).length, 300);
});

test("back-off never retries faster than the configured interval allows", () => {
  const s = { ...DEFAULT_SETTINGS, codexQuotaMinutes: 10 };
  assert.deepEqual([0, 1, 2, 3, 9].map((f) => nextDelayMs("claude_quota", DEFAULT_SETTINGS, f) / 60_000), [30, 5, 15, 30, 30]);
  assert.deepEqual([0, 1, 2, 3].map((f) => nextDelayMs("codex_quota", s, f) / 60_000), [10, 5, 10, 10]);
  assert.equal(nextDelayMs("claude_token_usage", { ...DEFAULT_SETTINGS, tokenUsageMinutes: 2 }, 1), 120_000);
});

test("scheduler runs each channel on its own clock and re-reads settings without restart", async () => {
  let clock = 0;
  const controller = new AbortController();
  const runs = new Map<Channel, number[]>(CHANNELS.map((c) => [c, []]));
  let settings = { ...DEFAULT_SETTINGS };
  const stateDir = tempDir("usagehub-scheduler-");
  try {
    await runScheduler({
      signal: controller.signal,
      now: () => clock,
      sleep: async (ms) => {
        clock += ms;
        if (clock >= 120 * 60_000) settings = { ...settings, claudeQuotaMinutes: 10 };
        if (clock > 180 * 60_000) controller.abort();
      },
      settings: () => ({ path: "test", settings, warnings: [], createdTemplate: false }),
      config: () => ({ ...testConfig(stateDir), installationId: "install-sched-01" }),
      log: () => {},
      report: () => {},
      run: async (channel) => {
        runs.get(channel)!.push(clock / 60_000);
        return channel === "codex_quota"
          ? { channel, outcome: { error: "codex down" }, summary: "" }
          : { channel, outcome: { uploadedAt: "x" }, summary: "" };
      },
    });
  } finally {
    rmSync(stateDir, { recursive: true, force: true });
  }
  assert.deepEqual(runs.get("claude_quota"), [0, 30, 60, 90, 120, 130, 140, 150, 160, 170, 180]);
  assert.deepEqual(runs.get("codex_quota"), [0, 5, 20, 50, 80, 110, 140, 170]);
  assert.deepEqual(runs.get("claude_token_usage"), [0, 30, 60, 90, 120, 150, 180]);
});

test("an upload lock left by a dead process is taken over immediately", async () => {
  const { spawnSync } = await import("node:child_process");
  const { tryAcquireWorkerLock } = await import("../src/shared/queue.js");
  const stateDir = tempDir("usagehub-lock-");
  try {
    const dead = spawnSync(process.execPath, ["-e", "process.exit(0)"]).pid;
    writeFileSync(join(stateDir, "upload-worker.lock"), `${dead}\n`, { mode: 0o600 });
    const release = tryAcquireWorkerLock(stateDir);
    assert.ok(release, "dead owner lock should be reclaimed");
    assert.equal(tryAcquireWorkerLock(stateDir), null, "live lock still excludes others");
    release();
    writeFileSync(join(stateDir, "upload-worker.lock"), `${process.ppid}\n`, { mode: 0o600 });
    assert.equal(tryAcquireWorkerLock(stateDir), null, "fresh lock of a live process is respected");
  } finally {
    rmSync(stateDir, { recursive: true, force: true });
  }
});
