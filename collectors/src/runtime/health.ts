import { mkdirSync, readFileSync, renameSync, writeFileSync } from "node:fs";
import { join } from "node:path";

export const CHANNELS = ["claude_quota", "codex_quota", "claude_token_usage", "codex_token_usage"] as const;
export type Channel = (typeof CHANNELS)[number];

export type ChannelHealth = {
  observedAt?: string;
  uploadedAt?: string;
  lastAttemptAt?: string;
  lastError?: string;
  lastErrorAt?: string;
};

export type HealthFile = {
  checkedAt: string;
  channels: Partial<Record<Channel, ChannelHealth>>;
  pending: number;
};

export function healthPath(stateDir: string): string {
  return join(stateDir, "collector-health.json");
}

export function readHealth(stateDir: string): HealthFile {
  try {
    const parsed = JSON.parse(readFileSync(healthPath(stateDir), "utf8")) as Partial<HealthFile>;
    if (parsed && typeof parsed === "object" && parsed.channels && typeof parsed.channels === "object") {
      return { checkedAt: String(parsed.checkedAt ?? ""), channels: parsed.channels, pending: Number(parsed.pending) || 0 };
    }
  } catch {
    // Missing or corrupt health is rebuilt from the next outcome.
  }
  return { checkedAt: "", channels: {}, pending: 0 };
}

/** A channel run: what was observed / delivered, and the failure reason if any step failed. */
export type ChannelOutcome = { observedAt?: string; uploadedAt?: string; error?: string };

/** Read-modify-write of one channel; atomic rename keeps concurrent readers safe. */
export function recordOutcome(stateDir: string, channel: Channel, outcome: ChannelOutcome, pending: number, now = Date.now()): HealthFile {
  const health = readHealth(stateDir);
  const at = new Date(now).toISOString();
  const previous = health.channels[channel] ?? {};
  const next: ChannelHealth = { ...previous, lastAttemptAt: at };
  if (outcome.observedAt) next.observedAt = outcome.observedAt;
  if (outcome.uploadedAt) next.uploadedAt = outcome.uploadedAt;
  if (outcome.error) {
    next.lastError = outcome.error;
    next.lastErrorAt = at;
  } else {
    delete next.lastError;
    delete next.lastErrorAt;
  }
  health.channels[channel] = next;
  health.checkedAt = at;
  health.pending = pending;
  mkdirSync(stateDir, { recursive: true, mode: 0o700 });
  const file = healthPath(stateDir);
  const temporary = `${file}.${process.pid}.tmp`;
  writeFileSync(temporary, JSON.stringify(health), { encoding: "utf8", mode: 0o600 });
  renameSync(temporary, file);
  return health;
}
