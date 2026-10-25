import { loadConfig, type CollectorConfig } from "../shared/config.js";
import { getInstallationId } from "../shared/installation.js";
import { enqueue } from "../shared/queue.js";
import { uploadPending } from "../shared/uploader.js";
import type { CodexPayload } from "../shared/types.js";
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
