import assert from "node:assert/strict";
import { createHmac } from "node:crypto";
import { mkdtempSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import test from "node:test";
import type { CollectorConfig } from "../src/shared/config.js";
import { enqueue, listPending } from "../src/shared/queue.js";
import { createSignedHeaders, sha256Hex } from "../src/shared/signer.js";
import { uploadPending } from "../src/shared/uploader.js";

test("HMAC headers match the documented replay-protected canonical string", () => {
  const body = '{"sample":true}';
  const key = "0123456789abcdef0123456789abcdef";
  const timestamp = 1_788_360_000;
  const nonce = "nonce_0123456789abcdef";
  const deviceId = "installation_012345";
  const headers = createSignedHeaders(body, key, deviceId, timestamp, nonce);
  const hash = sha256Hex(body);
  const expected = createHmac("sha256", key)
    .update(`${timestamp}\n${nonce}\n${deviceId}\n${hash}`)
    .digest("hex");
  assert.equal(headers["x-content-sha256"], hash);
  assert.equal(headers["x-signature"], expected);
  assert.equal(headers["x-device-id"], deviceId);
});

test("uploader sends bearer authentication and signed strict bodies, then retains records after 5xx", async () => {
  const stateDir = mkdtempSync(join(tmpdir(), "usagehub-upload-"));
  const payload = {
    installation_id: "installation_012345",
    observed_at: 1_788_372_000,
    rate_limits: { five_hour: { used_percentage: 1, resets_at: 2 } },
  };
  const config: CollectorConfig = {
    apiUrl: "http://127.0.0.1:18780",
    ingestToken: "0123456789abcdef0123456789abcdef",
    stateDir,
    timeoutMs: 100,
    maxQueueItems: 2,
  };
  try {
    enqueue(stateDir, { provider: "claude", payload }, 2);
    let capturedHeaders: Headers | undefined;
    let capturedBody = "";
    let capturedUrl = "";
    const successfulFetch: typeof fetch = async (input, init) => {
      capturedUrl = String(input);
      capturedHeaders = new Headers(init?.headers);
      capturedBody = String(init?.body);
      return new Response(null, { status: 204 });
    };
    const first = await uploadPending(config, successfulFetch);
    assert.deepEqual(first, { sent: 1, pending: 0 });
    assert.equal(capturedBody, JSON.stringify(payload));
    assert.equal(capturedHeaders?.get("x-content-sha256"), sha256Hex(capturedBody));
    assert.equal(capturedHeaders?.get("authorization"), "Bearer 0123456789abcdef0123456789abcdef");
    assert.ok(capturedHeaders?.get("x-timestamp"));
    assert.ok(capturedHeaders?.get("x-nonce"));
    assert.ok(capturedHeaders?.get("x-signature"));
    assert.equal(capturedBody.includes("cwd"), false);
    assert.equal(capturedUrl, "http://127.0.0.1:18780/v1/ingest/claude");

    const tokenPayload = {
      installation_id: "installation_012345",
      observed_at: 1_788_372_000,
      today_tokens: 1,
      total_tokens: 2,
      today_cost_usd: 0.1,
      total_cost_usd: 0.2,
      cost_label: "标准 API 等价费用" as const,
      source: "ccusage" as const,
    };
    enqueue(stateDir, { provider: "claude", kind: "token_usage", payload: tokenPayload }, 2);
    assert.deepEqual(await uploadPending(config, successfulFetch), { sent: 1, pending: 0 });
    assert.equal(capturedUrl, "http://127.0.0.1:18780/v1/ingest/claude/token-usage");
    assert.equal(capturedBody, JSON.stringify(tokenPayload));

    enqueue(stateDir, { provider: "claude", payload }, 2);
    const failed = await uploadPending(config, async () => new Response(null, { status: 503 }));
    assert.deepEqual(failed, { sent: 0, pending: 1 });
    assert.equal(listPending(stateDir).length, 1);
  } finally {
    rmSync(stateDir, { recursive: true, force: true });
  }
});
