import { setTimeout as delay } from "node:timers/promises";
import { loadConfig, type CollectorConfig } from "../shared/config.js";
import { getInstallationId } from "../shared/installation.js";
import { loadSettings, type CollectorSettings, type LoadedSettings } from "../shared/settings.js";
import { CHANNELS, type Channel } from "./health.js";
import { runChannel, type ChannelDeps, type ChannelResult } from "./channels.js";

export const BACKOFF_MINUTES = [5, 15, 30] as const;
const MAX_SLEEP_MS = 60_000;

export function intervalMinutes(channel: Channel, settings: CollectorSettings): number {
  if (channel === "claude_quota") return settings.claudeQuotaMinutes;
  if (channel === "codex_quota") return settings.codexQuotaMinutes;
  return settings.tokenUsageMinutes;
}

/** Wait before the next run: the configured interval, or after n consecutive failures min(interval, 5/15/30 min). */
export function nextDelayMs(channel: Channel, settings: CollectorSettings, failures: number): number {
  const interval = intervalMinutes(channel, settings);
  if (failures <= 0) return interval * 60_000;
  const backoff = BACKOFF_MINUTES[Math.min(failures, BACKOFF_MINUTES.length) - 1]!;
  return Math.min(interval, backoff) * 60_000;
}

type ChannelState = { lastRunAt: number; failures: number };

export type SchedulerOptions = {
  signal?: AbortSignal;
  env?: NodeJS.ProcessEnv;
  now?: () => number;
  sleep?: (ms: number, signal?: AbortSignal) => Promise<void>;
  run?: (channel: Channel, deps: ChannelDeps) => Promise<ChannelResult>;
  config?: () => CollectorConfig;
  settings?: () => LoadedSettings;
  log?: (line: string) => void;
  report?: (line: string) => void;
};

/**
 * Each channel runs on its own interval and back-off. Settings are re-read on every wake (at most
 * one minute apart), so edits to the config file apply without a restart.
 */
export async function runScheduler(options: SchedulerOptions = {}): Promise<void> {
  const env = options.env ?? process.env;
  const now = options.now ?? Date.now;
  const sleep = options.sleep ?? (async (ms: number, signal?: AbortSignal) => { await delay(ms, undefined, { signal }); });
  const run = options.run ?? runChannel;
  const log = options.log ?? ((line: string) => process.stderr.write(`${line}\n`));
  const report = options.report ?? ((line: string) => process.stdout.write(`${line}\n`));
  const states = new Map<Channel, ChannelState>(CHANNELS.map((channel) => [channel, { lastRunAt: -Infinity, failures: 0 }]));
  let lastWarnings = "";

  while (!options.signal?.aborted) {
    const loaded = (options.settings ?? (() => loadSettings(env)))();
    const warnings = loaded.warnings.join("; ");
    if (warnings && warnings !== lastWarnings) log(`[usagehub] settings warning (${loaded.path}): ${warnings}`);
    if (loaded.createdTemplate) log(`[usagehub] wrote default settings to ${loaded.path}`);
    lastWarnings = warnings;

    for (const channel of CHANNELS) {
      if (options.signal?.aborted) return;
      const state = states.get(channel)!;
      if (now() < state.lastRunAt + nextDelayMs(channel, loaded.settings, state.failures)) continue;
      let result: ChannelResult;
      try {
        const config = (options.config ?? (() => loadConfig(env)))();
        const installationId = getInstallationId(config.stateDir, config.installationId);
        result = await run(channel, { config, settings: loaded.settings, installationId });
      } catch (error) {
        result = { channel, outcome: { error: (error as Error).message }, summary: "" };
      }
      state.lastRunAt = now();
      state.failures = result.outcome.error ? state.failures + 1 : 0;
      const waitMinutes = Math.round(nextDelayMs(channel, loaded.settings, state.failures) / 60_000);
      if (result.outcome.error) log(`[usagehub] ${channel} failed: ${result.outcome.error}; retry in ${waitMinutes} min`);
      report(JSON.stringify({ event: "channel", channel, at: new Date(state.lastRunAt).toISOString(), ok: !result.outcome.error, summary: result.summary || undefined, observedAt: result.outcome.observedAt, uploadedAt: result.outcome.uploadedAt, nextInMinutes: waitMinutes }));
    }

    const current = now();
    let wake = MAX_SLEEP_MS;
    for (const channel of CHANNELS) {
      const state = states.get(channel)!;
      wake = Math.min(wake, Math.max(1_000, state.lastRunAt + nextDelayMs(channel, loaded.settings, state.failures) - current));
    }
    try {
      await sleep(wake, options.signal);
    } catch {
      return;
    }
  }
}
