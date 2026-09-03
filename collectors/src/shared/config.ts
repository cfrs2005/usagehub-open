import { homedir } from "node:os";
import { join, resolve } from "node:path";

export type CollectorConfig = {
  apiUrl: string;
  ingestToken: string;
  installationId?: string;
  stateDir: string;
  timeoutMs: number;
  maxQueueItems: number;
};

function positiveInteger(value: string | undefined, fallback: number): number {
  const parsed = Number(value);
  return Number.isInteger(parsed) && parsed > 0 ? parsed : fallback;
}

export function loadConfig(env: NodeJS.ProcessEnv = process.env): CollectorConfig {
  return {
    apiUrl: (env.USAGEHUB_API_URL ?? "https://u.80aj.com").replace(/\/+$/, ""),
    ingestToken: env.USAGEHUB_INGEST_TOKEN ?? "",
    installationId: env.USAGEHUB_INSTALLATION_ID,
    stateDir: resolve(
      env.USAGEHUB_STATE_DIR ?? join(homedir(), ".local", "state", "usagehub"),
    ),
    timeoutMs: positiveInteger(env.USAGEHUB_REQUEST_TIMEOUT_MS, 5_000),
    maxQueueItems: positiveInteger(env.USAGEHUB_QUEUE_MAX_ITEMS, 64),
  };
}

export function requireUploadConfig(config: CollectorConfig): void {
  if (!config.apiUrl.startsWith("https://") && !config.apiUrl.startsWith("http://127.0.0.1")) {
    throw new Error("USAGEHUB_API_URL must use HTTPS (loopback HTTP is allowed for tests)");
  }
  if (config.ingestToken.length < 16) {
    throw new Error("USAGEHUB_INGEST_TOKEN must contain at least 16 characters");
  }
}
