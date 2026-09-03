export type ClaudeWindow = {
  used_percentage: number;
  resets_at: number;
};

export type ClaudePayload = {
  installation_id: string;
  observed_at: number;
  claude_version?: string;
  rate_limits: {
    five_hour?: ClaudeWindow;
    seven_day?: ClaudeWindow;
  };
};

export type CodexWindow = {
  usedPercent: number;
  windowDurationMins: number;
  resetsAt: number;
};

export type CodexLimit = {
  primary?: CodexWindow | null;
  secondary?: CodexWindow | null;
};

export type CodexPayload = {
  installation_id: string;
  observed_at: number;
  rate_limits: {
    rateLimitsByLimitId: Record<string, CodexLimit>;
  };
};

export type Provider = "claude" | "codex";
export type IngestPayload = ClaudePayload | CodexPayload;

export type TokenUsagePayload = {
  installation_id: string;
  observed_at: number;
  today_tokens: number;
  total_tokens: number;
  today_cost_usd: number;
  total_cost_usd: number;
  cost_label: "标准 API 等价费用";
  source: "ccusage" | "ccusage_codex";
};

export type QueueRecord = {
  provider: Provider;
  kind?: "quota" | "token_usage";
  payload: IngestPayload | TokenUsagePayload;
};
