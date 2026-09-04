import { chmodSync, mkdirSync, renameSync, writeFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { randomUUID } from "node:crypto";

type Fetcher = (input: string, init: RequestInit) => Promise<Response>;

export type EnrollmentOptions = {
  apiUrl: string;
  code: string;
  installationId: string;
  stateDir: string;
};

function endpoint(apiUrl: string): string {
  const normalized = apiUrl.replace(/\/+$/, "");
  if (!normalized.startsWith("https://") && !normalized.startsWith("http://127.0.0.1")) {
    throw new Error("UsageHub enrollment requires HTTPS");
  }
  return `${normalized}/v1/collector-enrollments/redeem`;
}

export function saveIngestToken(stateDir: string, token: string): string {
  if (!/^uh_ingest_[A-Za-z0-9_-]{20,200}$/.test(token)) throw new Error("UsageHub returned an invalid collector token");
  const file = join(stateDir, "ingest-token");
  mkdirSync(dirname(file), { recursive: true, mode: 0o700 });
  const temporary = `${file}.${process.pid}.${randomUUID()}.tmp`;
  writeFileSync(temporary, `${token}\n`, { encoding: "utf8", mode: 0o600, flag: "wx" });
  renameSync(temporary, file);
  chmodSync(file, 0o600);
  return file;
}

export async function redeemCollectorEnrollment(options: EnrollmentOptions, fetcher: Fetcher = fetch): Promise<{ tokenFile: string; expiresAt: string | null }> {
  if (!/^uh_enroll_[A-Za-z0-9_-]{20,200}$/.test(options.code)) throw new Error("Collector enrollment code is invalid");
  const response = await fetcher(endpoint(options.apiUrl), {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({ code: options.code, installationId: options.installationId }),
  });
  const value = await response.json().catch(() => null) as { token?: unknown; expiresAt?: unknown } | null;
  if (!response.ok || !value || typeof value.token !== "string") throw new Error("Collector enrollment failed or expired");
  return {
    tokenFile: saveIngestToken(options.stateDir, value.token),
    expiresAt: typeof value.expiresAt === "string" ? value.expiresAt : null,
  };
}
