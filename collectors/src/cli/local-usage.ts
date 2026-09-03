#!/usr/bin/env node
import { loadConfig } from "../shared/config.js";
import { getInstallationId } from "../shared/installation.js";
import { enqueue } from "../shared/queue.js";
import { uploadPending } from "../shared/uploader.js";
import {
  LocalUsageCollector,
  toTokenUsagePayload,
} from "../local-usage/collector.js";

async function main(): Promise<void> {
  const config = loadConfig();
  const force = process.argv.includes("--force");
  const noUpload = process.argv.includes("--no-upload");
  const collector = new LocalUsageCollector({
    stateDir: config.stateDir,
  });
  const results = await Promise.all([collector.collect("claude", force), collector.collect("codex", force)]);
  if (!noUpload) {
    const installationId = getInstallationId(config.stateDir, config.installationId);
    for (const result of results) {
      if (result.cached) continue;
      const payload = toTokenUsagePayload(result, installationId);
      if (payload) {
        enqueue(config.stateDir, { provider: result.provider, kind: "token_usage", payload }, config.maxQueueItems);
      }
    }
    await uploadPending(config);
  }
  process.stdout.write(`${JSON.stringify(results, null, 2)}\n`);
}

main().catch(() => {
  process.exitCode = 1;
});
