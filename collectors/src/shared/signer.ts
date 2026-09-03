import { createHash, createHmac, randomBytes } from "node:crypto";

export type SignedHeaders = Record<string, string>;

export function sha256Hex(value: string): string {
  return createHash("sha256").update(value, "utf8").digest("hex");
}

export function createSignedHeaders(
  rawBody: string,
  ingestToken: string,
  deviceId: string,
  timestamp = Math.floor(Date.now() / 1_000),
  nonce = randomBytes(18).toString("base64url"),
): SignedHeaders {
  if (!/^[A-Za-z0-9_-]{16,128}$/.test(nonce)) throw new Error("invalid nonce");
  const bodyHash = sha256Hex(rawBody);
  const canonical = `${timestamp}\n${nonce}\n${deviceId}\n${bodyHash}`;
  const signature = createHmac("sha256", ingestToken).update(canonical, "utf8").digest("hex");
  return {
    "content-type": "application/json",
    "x-device-id": deviceId,
    "x-timestamp": String(timestamp),
    "x-nonce": nonce,
    "x-content-sha256": bodyHash,
    "x-signature": signature,
  };
}
