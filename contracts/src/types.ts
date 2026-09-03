export type Provider = "claude" | "codex";
export type TokenUsageSource = "ccusage" | "ccusage_codex";
export type TokenCostLabel = "标准 API 等价费用";

export interface ClaudeRateLimit {
  used_percentage: number | null;
  resets_at: number | null;
}

export interface ClaudeIngestBody {
  installation_id: string;
  observed_at: number;
  claude_version?: string;
  rate_limits: {
    five_hour?: ClaudeRateLimit | null;
    seven_day?: ClaudeRateLimit | null;
  };
}

export interface CodexRateWindow {
  usedPercent: number | null;
  windowDurationMins: number;
  resetsAt: number | null;
}

export interface CodexLimitBucket {
  primary?: CodexRateWindow | null;
  secondary?: CodexRateWindow | null;
}

export interface CodexIngestBody {
  installation_id: string;
  observed_at: number;
  rate_limits: {
    rateLimitsByLimitId: Record<string, CodexLimitBucket>;
  };
}

export type IngestBody = ClaudeIngestBody | CodexIngestBody;

export interface TokenUsageIngestBody {
  installation_id: string;
  observed_at: number;
  today_tokens: number;
  total_tokens: number;
  today_cost_usd: number;
  total_cost_usd: number;
  cost_label: TokenCostLabel;
  source: TokenUsageSource;
}

export interface NormalizedTokenUsage {
  provider: Provider;
  installationId: string;
  observedAt: number;
  todayTokens: number;
  totalTokens: number;
  todayCostUsd: number;
  totalCostUsd: number;
  costLabel: TokenCostLabel;
  source: TokenUsageSource;
}

export type WindowKind = "short" | "weekly" | "other";

export interface NormalizedWindow {
  limitId: string;
  kind: WindowKind;
  durationMinutes: number;
  usedPercent: number | null;
  resetsAt: number | null;
}

export interface NormalizedSnapshot {
  provider: Provider;
  source: "official_statusline" | "official_app_server";
  installationId: string;
  observedAt: number;
  claudeVersion?: string;
  windows: NormalizedWindow[];
}

export interface ValidationResult<T> {
  valid: boolean;
  value?: T;
  errors: string[];
}

export interface DashboardProvider {
  provider: Provider;
  status: "ok" | "missing";
  source: "official_statusline" | "official_app_server";
  observedAt: string | null;
  receivedAt: string | null;
  stale: boolean;
  windows: NormalizedWindow[];
  tokenUsage: DashboardTokenUsage;
}

export interface DashboardTokenUsage {
  status: "ok" | "missing";
  todayTokens: number | null;
  totalTokens: number | null;
  todayCostUsd: number | null;
  totalCostUsd: number | null;
  costLabel: TokenCostLabel;
  source: TokenUsageSource | null;
  observedAt: string | null;
  stale: boolean;
}

export interface DashboardResponse {
  serverTime: string;
  providers: DashboardProvider[];
}
