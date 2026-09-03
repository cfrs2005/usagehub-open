import type { CodexLimit, CodexPayload, CodexWindow } from "../shared/types.js";

type UnknownRecord = Record<string, unknown>;

function isRecord(value: unknown): value is UnknownRecord {
  return Boolean(value) && typeof value === "object" && !Array.isArray(value);
}

function projectWindow(value: unknown): CodexWindow | null {
  if (!isRecord(value)) return null;
  const { usedPercent, windowDurationMins, resetsAt } = value;
  if (
    typeof usedPercent !== "number" ||
    !Number.isFinite(usedPercent) ||
    typeof windowDurationMins !== "number" ||
    !Number.isFinite(windowDurationMins) ||
    typeof resetsAt !== "number" ||
    !Number.isFinite(resetsAt)
  ) {
    return null;
  }
  return { usedPercent, windowDurationMins, resetsAt };
}

function safeLimitId(value: string): boolean {
  return (
    /^[A-Za-z0-9_.:-]{1,128}$/.test(value) &&
    value !== "__proto__" &&
    value !== "constructor" &&
    value !== "prototype"
  );
}

export function projectCodexRateLimits(
  rpcResult: unknown,
  installationId: string,
  observedAt = Math.floor(Date.now() / 1_000),
): CodexPayload | null {
  if (!isRecord(rpcResult) || !isRecord(rpcResult.rateLimitsByLimitId)) return null;
  const projected: Record<string, CodexLimit> = {};

  for (const [limitId, rawLimit] of Object.entries(rpcResult.rateLimitsByLimitId)) {
    if (!safeLimitId(limitId) || !isRecord(rawLimit)) continue;
    const limit: CodexLimit = {};
    if (Object.hasOwn(rawLimit, "primary")) {
      if (rawLimit.primary === null) limit.primary = null;
      else {
        const primary = projectWindow(rawLimit.primary);
        if (primary) limit.primary = primary;
      }
    }
    if (Object.hasOwn(rawLimit, "secondary")) {
      if (rawLimit.secondary === null) limit.secondary = null;
      else {
        const secondary = projectWindow(rawLimit.secondary);
        if (secondary) limit.secondary = secondary;
      }
    }
    if (limit.primary === undefined && limit.secondary === undefined) continue;
    projected[limitId] = limit;
  }

  if (Object.keys(projected).length === 0) return null;
  return {
    installation_id: installationId,
    observed_at: observedAt,
    rate_limits: { rateLimitsByLimitId: projected },
  };
}
