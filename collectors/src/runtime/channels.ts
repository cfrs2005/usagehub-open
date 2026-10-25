import { existsSync } from "node:fs";
import { setTimeout as delay } from "node:timers/promises";
import { probeClaudeUsage } from "../claude/usage-probe.js";
import { collectCodexSamples } from "../codex/collector.js";
import { LocalUsageCollector, toTokenUsagePayload } from "../local-usage/collector.js";
import type { CollectorConfig } from "../shared/config.js";
import { enqueue, listPending } from "../shared/queue.js";
import type { CollectorSettings } from "../shared/settings.js";
import type { ClaudePayload, CodexPayload, QueueRecord } from "../shared/types.js";
import { uploadPending, type UploadResult } from "../shared/uploader.js";
import { zonedDate } from "../shared/zoned-time.js";
import { recordOutcome, type Channel, type ChannelOutcome } from "./health.js";

export type ChannelDeps = {
  config: CollectorConfig;
  settings: CollectorSettings;
  installationId: string;
  now?: () => number;
  claudeProbe?: () => Promise<ClaudePayload>;
  codexRead?: () => Promise<CodexPayload>;
  localCollector?: LocalUsageCollector;
  upload?: (config: CollectorConfig) => Promise<UploadResult>;
  /** Extra upload attempts while another process holds the queue lock. */
  lockRetries?: number;
  lockRetryDelayMs?: number;
  writeHealth?: boolean;
};

export type ChannelResult = { channel: Channel; outcome: ChannelOutcome; summary: string };

/** One line, bounded, with anything token-shaped removed. */
export function sanitizeError(error: unknown): string {
  const message = error instanceof Error ? error.message : String(error);
  return message
    .replace(/uh_ingest_[A-Za-z0-9_-]+/g, "[redacted]")
    .replace(/(bearer|token|authorization)(["'\s:=]+)[A-Za-z0-9._~+/-]{12,}/gi, "$1$2[redacted]")
    .replace(/\s+/g, " ")
    .trim()
    .slice(0, 300) || "unknown error";
}

type Collected = { record: QueueRecord; observedAtSec: number; summary: string };

async function collect(channel: Channel, deps: ChannelDeps): Promise<Collected> {
  const { config, installationId, settings } = deps;
  if (channel === "claude_quota") {
    const payload = await (deps.claudeProbe ?? (() => probeClaudeUsage({ stateDir: config.stateDir, installationId, now: deps.now })))();
    const { five_hour, seven_day } = payload.rate_limits;
    return {
      record: { provider: "claude", kind: "quota", payload },
      observedAtSec: payload.observed_at,
      summary: `5h ${five_hour ? `${five_hour.used_percentage}%` : "none"} / 7d ${seven_day?.used_percentage}%`,
    };
  }
  if (channel === "codex_quota") {
    const read = deps.codexRead ?? (async () => (await collectCodexSamples(1, { config, upload: false }))[0]!);
    const payload = await read();
    const buckets = Object.keys(payload.rate_limits.rateLimitsByLimitId).length;
    return { record: { provider: "codex", kind: "quota", payload }, observedAtSec: payload.observed_at, summary: `${buckets} official buckets` };
  }
  const provider = channel === "claude_token_usage" ? "claude" : "codex";
  const collector = deps.localCollector ?? new LocalUsageCollector({ stateDir: config.stateDir, timeZone: settings.timezone, now: deps.now });
  const result = await collector.collect(provider, true);
  if (result.status !== "ok" || result.stale) throw new Error(result.error ?? `${result.source} unavailable`);
  const payload = toTokenUsagePayload(result, installationId);
  if (!payload) throw new Error(`${result.source} returned incomplete totals`);
  const today = zonedDate((deps.now ?? Date.now)(), settings.timezone);
  return {
    record: { provider, kind: "token_usage", payload },
    observedAtSec: payload.observed_at,
    summary: `today(${today} ${settings.timezone}) ${payload.today_tokens} tokens $${payload.today_cost_usd}; total ${payload.total_tokens} tokens`,
  };
}

async function deliver(path: string, deps: ChannelDeps): Promise<{ delivered: boolean; error?: string }> {
  const upload = deps.upload ?? uploadPending;
  const retries = deps.lockRetries ?? 30;
  let last: UploadResult = { sent: 0, pending: 0 };
  for (let attempt = 0; attempt <= retries; attempt += 1) {
    last = await upload(deps.config);
    if (!existsSync(path)) return { delivered: true };
    if (!last.locked) break;
    await delay(deps.lockRetryDelayMs ?? 2_000);
  }
  return { delivered: false, error: last.error ?? (last.locked ? "queue locked by another uploader" : "record still queued") };
}

/** Runs one channel end to end. Never throws: every failure becomes `outcome.error`. */
export async function runChannel(channel: Channel, deps: ChannelDeps): Promise<ChannelResult> {
  const now = deps.now ?? Date.now;
  let outcome: ChannelOutcome;
  let summary = "";
  try {
    const collected = await collect(channel, deps);
    summary = collected.summary;
    const path = enqueue(deps.config.stateDir, collected.record, deps.config.maxQueueItems);
    const observedAt = new Date(collected.observedAtSec * 1_000).toISOString();
    const delivery = await deliver(path, deps).catch((error: unknown) => ({ delivered: false, error: sanitizeError(error) }));
    outcome = delivery.delivered
      ? { observedAt, uploadedAt: new Date(now()).toISOString() }
      : { observedAt, error: `upload pending: ${sanitizeError(delivery.error)}` };
  } catch (error) {
    outcome = { error: sanitizeError(error) };
  }
  if (deps.writeHealth !== false) {
    try {
      recordOutcome(deps.config.stateDir, channel, outcome, listPending(deps.config.stateDir).length, now());
    } catch {
      // Health is diagnostic only; collection results must not depend on it.
    }
  }
  return { channel, outcome, summary };
}
