import { setTimeout as delay } from "node:timers/promises";
import { loadConfig, type CollectorConfig } from "../shared/config.js";
import { getInstallationId } from "../shared/installation.js";
import { enqueue } from "../shared/queue.js";
import { uploadPending } from "../shared/uploader.js";
import type { CodexPayload } from "../shared/types.js";
import {
  LocalUsageCollector,
  toTokenUsagePayload,
} from "../local-usage/collector.js";
import { CodexAppServerClient } from "./app-server.js";
import { projectCodexRateLimits } from "./project.js";

export type CodexCollectionOptions = {
  upload?: boolean;
  config?: CollectorConfig;
  client?: CodexAppServerClient;
  observedAt?: () => number;
};

export async function collectCodexSamples(
  count: number,
  options: CodexCollectionOptions = {},
): Promise<CodexPayload[]> {
  if (!Number.isInteger(count) || count < 1 || count > 100) throw new Error("count must be between 1 and 100");
  const config = options.config ?? loadConfig();
  const installationId = getInstallationId(config.stateDir, config.installationId);
  const client = options.client ?? new CodexAppServerClient();
  const ownsClient = !options.client;
  const samples: CodexPayload[] = [];
  try {
    await client.start();
    for (let index = 0; index < count; index += 1) {
      const result = await client.readRateLimits();
      const payload = projectCodexRateLimits(
        result,
        installationId,
        options.observedAt?.() ?? Math.floor(Date.now() / 1_000),
      );
      if (!payload) throw new Error("Codex app-server returned no valid rate limit buckets");
      samples.push(payload);
      if (options.upload !== false) {
        enqueue(config.stateDir, { provider: "codex", payload }, config.maxQueueItems);
        const outcome = await uploadPending(config);
        if (outcome.pending > 0) throw new Error("Codex sample remains queued after upload");
      }
    }
    return samples;
  } finally {
    if (ownsClient) await client.close();
  }
}

export type CodexLoopOptions = {
  intervalMs?: number;
  backoffMs?: number[];
  signal?: AbortSignal;
  config?: CollectorConfig;
};

export async function runCodexLoop(options: CodexLoopOptions = {}): Promise<void> {
  const intervalMs = options.intervalMs ?? 120_000;
  const backoff = options.backoffMs ?? [300_000, 900_000, 1_800_000];
  let failures = 0;
  while (!options.signal?.aborted) {
    try {
      const config = options.config ?? loadConfig();
      await collectCodexSamples(1, { config });
      const localCollector = new LocalUsageCollector({ stateDir: config.stateDir });
      const installationId = getInstallationId(config.stateDir, config.installationId);
      const localResults = await Promise.all([
        localCollector.collect("claude"),
        localCollector.collect("codex"),
      ]);
      for (const result of localResults) {
        if (result.cached) continue;
        const payload = toTokenUsagePayload(result, installationId);
        if (payload) {
          enqueue(config.stateDir, { provider: result.provider, kind: "token_usage", payload }, config.maxQueueItems);
        }
      }
      await uploadPending(config);
      failures = 0;
      await delay(intervalMs, undefined, { signal: options.signal });
    } catch (error) {
      if (options.signal?.aborted) break;
      const wait = backoff[Math.min(failures, backoff.length - 1)] ?? 1_800_000;
      failures += 1;
      process.stderr.write(`Codex collector retry scheduled in ${Math.round(wait / 60_000)} minutes\n`);
      try {
        await delay(wait, undefined, { signal: options.signal });
      } catch {
        break;
      }
    }
  }
}
