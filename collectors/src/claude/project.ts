import type { ClaudePayload, ClaudeWindow } from "../shared/types.js";

type UnknownRecord = Record<string, unknown>;

function isRecord(value: unknown): value is UnknownRecord {
  return Boolean(value) && typeof value === "object" && !Array.isArray(value);
}

function finiteNumber(value: unknown): number | null {
  return typeof value === "number" && Number.isFinite(value) ? value : null;
}

function projectWindow(value: unknown): ClaudeWindow | null {
  if (!isRecord(value)) return null;
  const used = finiteNumber(value.used_percentage);
  const reset = finiteNumber(value.resets_at);
  if (used === null || reset === null) return null;
  return { used_percentage: used, resets_at: reset };
}

function versionFrom(input: UnknownRecord): string | undefined {
  const value = input.claude_version ?? input.version;
  if (typeof value !== "string" || value.length === 0 || value.length > 64) return undefined;
  return value;
}

export function projectClaudeStatusLine(
  raw: unknown,
  installationId: string,
  observedAt = Math.floor(Date.now() / 1_000),
): ClaudePayload | null {
  if (!isRecord(raw) || !isRecord(raw.rate_limits)) return null;

  const fiveHour = projectWindow(raw.rate_limits.five_hour);
  const sevenDay = projectWindow(raw.rate_limits.seven_day);
  if (!fiveHour && !sevenDay) return null;

  const rateLimits: ClaudePayload["rate_limits"] = {};
  if (fiveHour) rateLimits.five_hour = fiveHour;
  if (sevenDay) rateLimits.seven_day = sevenDay;

  const payload: ClaudePayload = {
    installation_id: installationId,
    observed_at: observedAt,
    rate_limits: rateLimits,
  };
  const version = versionFrom(raw);
  if (version) payload.claude_version = version;
  return payload;
}
