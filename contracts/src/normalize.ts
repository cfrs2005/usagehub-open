import type {
  ClaudeIngestBody,
  CodexIngestBody,
  NormalizedSnapshot,
  NormalizedTokenUsage,
  NormalizedWindow,
  Provider,
  TokenUsageIngestBody,
  WindowKind,
} from "./types.js";

export function normalizeTokenUsage(
  provider: Provider,
  body: TokenUsageIngestBody,
): NormalizedTokenUsage {
  return {
    provider,
    installationId: body.installation_id,
    observedAt: body.observed_at,
    todayTokens: body.today_tokens,
    totalTokens: body.total_tokens,
    todayCostUsd: body.today_cost_usd,
    totalCostUsd: body.total_cost_usd,
    costLabel: body.cost_label,
    source: body.source,
  };
}

export function classifyWindow(durationMinutes: number): WindowKind {
  if (durationMinutes <= 24 * 60) return "short";
  if (durationMinutes >= 6 * 24 * 60 && durationMinutes <= 8 * 24 * 60) return "weekly";
  return "other";
}

export function normalizeClaude(body: ClaudeIngestBody): NormalizedSnapshot {
  const windows: NormalizedWindow[] = [];
  const definitions = [
    ["five_hour", 300],
    ["seven_day", 10_080],
  ] as const;
  for (const [limitId, durationMinutes] of definitions) {
    const limit = body.rate_limits[limitId];
    if (!limit) continue;
    windows.push({
      limitId,
      kind: classifyWindow(durationMinutes),
      durationMinutes,
      usedPercent: limit.used_percentage,
      resetsAt: limit.resets_at,
    });
  }
  return {
    provider: "claude",
    source: "official_statusline",
    installationId: body.installation_id,
    observedAt: body.observed_at,
    ...(body.claude_version ? { claudeVersion: body.claude_version } : {}),
    windows,
  };
}

export function normalizeCodex(body: CodexIngestBody): NormalizedSnapshot {
  const windows: NormalizedWindow[] = [];
  const buckets = body.rate_limits.rateLimitsByLimitId;
  for (const limitId of Object.keys(buckets).sort()) {
    const bucket = buckets[limitId];
    if (!bucket) continue;
    for (const role of ["primary", "secondary"] as const) {
      const window = bucket[role];
      if (!window) continue;
      windows.push({
        limitId: `${limitId}:${role}`,
        kind: classifyWindow(window.windowDurationMins),
        durationMinutes: window.windowDurationMins,
        usedPercent: window.usedPercent,
        resetsAt: window.resetsAt,
      });
    }
  }
  windows.sort((left, right) =>
    left.limitId.localeCompare(right.limitId) || left.durationMinutes - right.durationMinutes,
  );
  return {
    provider: "codex",
    source: "official_app_server",
    installationId: body.installation_id,
    observedAt: body.observed_at,
    windows,
  };
}
