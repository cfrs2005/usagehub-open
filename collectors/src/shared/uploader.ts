import type { CollectorConfig } from "./config.js";
import { requireUploadConfig } from "./config.js";
import { listPending, readRecord, removeRecord, tryAcquireWorkerLock } from "./queue.js";
import { createSignedHeaders } from "./signer.js";
import type { QueueRecord } from "./types.js";

export type UploadResult = { sent: number; pending: number; locked?: true };

export async function uploadRecord(
  config: CollectorConfig,
  record: QueueRecord,
  fetchImplementation: typeof fetch = fetch,
): Promise<void> {
  requireUploadConfig(config);
  const rawBody = JSON.stringify(record.payload);
  const deviceId = record.payload.installation_id;
  const headers = createSignedHeaders(rawBody, config.ingestToken, deviceId);
  const controller = new AbortController();
  const timeout = setTimeout(() => controller.abort(), config.timeoutMs);
  try {
    const suffix = record.kind === "token_usage" ? "/token-usage" : "";
    const response = await fetchImplementation(`${config.apiUrl}/v1/ingest/${record.provider}${suffix}`, {
      method: "POST",
      headers: {
        ...headers,
        authorization: `Bearer ${config.ingestToken}`,
      },
      body: rawBody,
      signal: controller.signal,
    });
    if (!response.ok) throw new Error(`hub returned HTTP ${response.status}`);
  } finally {
    clearTimeout(timeout);
  }
}

export async function uploadPending(
  config: CollectorConfig,
  fetchImplementation: typeof fetch = fetch,
): Promise<UploadResult> {
  const release = tryAcquireWorkerLock(config.stateDir);
  if (!release) return { sent: 0, pending: listPending(config.stateDir).length, locked: true };
  let sent = 0;
  try {
    for (const path of listPending(config.stateDir)) {
      let record: QueueRecord;
      try {
        record = readRecord(path);
      } catch {
        removeRecord(path);
        continue;
      }
      try {
        await uploadRecord(config, record, fetchImplementation);
      } catch {
        break;
      }
      removeRecord(path);
      sent += 1;
    }
    return { sent, pending: listPending(config.stateDir).length };
  } finally {
    release();
  }
}
