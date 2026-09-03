import type {
  ClaudeIngestBody,
  ClaudeRateLimit,
  CodexIngestBody,
  CodexLimitBucket,
  CodexRateWindow,
  Provider,
  TokenUsageIngestBody,
  ValidationResult,
} from "./types.js";

const INSTALLATION_ID = /^[A-Za-z0-9][A-Za-z0-9._-]{7,127}$/;
const LIMIT_ID = /^[A-Za-z0-9][A-Za-z0-9._:/-]{0,127}$/;

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

function rejectUnknown(
  value: Record<string, unknown>,
  allowed: readonly string[],
  path: string,
  errors: string[],
): void {
  const allowlist = new Set(allowed);
  for (const key of Object.keys(value)) {
    if (!allowlist.has(key)) errors.push(`${path}.${key}: unknown field`);
  }
}

function unixSeconds(value: unknown, path: string, errors: string[], nullable = false): value is number | null {
  if (nullable && value === null) return true;
  if (!Number.isSafeInteger(value) || (value as number) < 0) {
    errors.push(`${path}: expected non-negative Unix seconds`);
    return false;
  }
  return true;
}

function usedPercent(value: unknown, path: string, errors: string[]): value is number | null {
  if (value === null) return true;
  if (typeof value !== "number" || !Number.isFinite(value) || value < 0 || value > 10_000) {
    errors.push(`${path}: expected null or a number from 0 to 10000`);
    return false;
  }
  return true;
}

function nonNegativeNumber(value: unknown, path: string, errors: string[]): value is number {
  if (typeof value !== "number" || !Number.isFinite(value) || value < 0) {
    errors.push(`${path}: expected a non-negative finite number`);
    return false;
  }
  return true;
}

function validateClaudeRateLimit(value: unknown, path: string, errors: string[]): value is ClaudeRateLimit {
  if (!isRecord(value)) {
    errors.push(`${path}: expected object or null`);
    return false;
  }
  rejectUnknown(value, ["used_percentage", "resets_at"], path, errors);
  if (!("used_percentage" in value)) errors.push(`${path}.used_percentage: required`);
  if (!("resets_at" in value)) errors.push(`${path}.resets_at: required`);
  usedPercent(value.used_percentage, `${path}.used_percentage`, errors);
  unixSeconds(value.resets_at, `${path}.resets_at`, errors, true);
  return errors.length === 0;
}

function validateCodexWindow(value: unknown, path: string, errors: string[]): value is CodexRateWindow {
  if (!isRecord(value)) {
    errors.push(`${path}: expected object or null`);
    return false;
  }
  rejectUnknown(value, ["usedPercent", "windowDurationMins", "resetsAt"], path, errors);
  for (const key of ["usedPercent", "windowDurationMins", "resetsAt"] as const) {
    if (!(key in value)) errors.push(`${path}.${key}: required`);
  }
  usedPercent(value.usedPercent, `${path}.usedPercent`, errors);
  if (!Number.isSafeInteger(value.windowDurationMins) || (value.windowDurationMins as number) <= 0) {
    errors.push(`${path}.windowDurationMins: expected a positive integer`);
  }
  unixSeconds(value.resetsAt, `${path}.resetsAt`, errors, true);
  return errors.length === 0;
}

function validateCodexBucket(value: unknown, path: string, errors: string[]): value is CodexLimitBucket {
  if (!isRecord(value)) {
    errors.push(`${path}: expected object`);
    return false;
  }
  rejectUnknown(value, ["primary", "secondary"], path, errors);
  if (!("primary" in value) && !("secondary" in value)) {
    errors.push(`${path}: expected primary or secondary`);
  }
  for (const role of ["primary", "secondary"] as const) {
    if (value[role] !== undefined && value[role] !== null) {
      validateCodexWindow(value[role], `${path}.${role}`, errors);
    }
  }
  return errors.length === 0;
}

export function validateClaudeIngest(value: unknown): ValidationResult<ClaudeIngestBody> {
  const errors: string[] = [];
  if (!isRecord(value)) return { valid: false, errors: ["body: expected object"] };
  rejectUnknown(value, ["installation_id", "observed_at", "claude_version", "rate_limits"], "body", errors);
  if (typeof value.installation_id !== "string" || !INSTALLATION_ID.test(value.installation_id)) {
    errors.push("body.installation_id: invalid installation ID");
  }
  unixSeconds(value.observed_at, "body.observed_at", errors);
  if (value.claude_version !== undefined && (typeof value.claude_version !== "string" || value.claude_version.length > 64)) {
    errors.push("body.claude_version: expected a string up to 64 characters");
  }
  if (!isRecord(value.rate_limits)) {
    errors.push("body.rate_limits: expected object");
  } else {
    rejectUnknown(value.rate_limits, ["five_hour", "seven_day"], "body.rate_limits", errors);
    for (const key of ["five_hour", "seven_day"] as const) {
      const limit = value.rate_limits[key];
      if (limit !== undefined && limit !== null) {
        validateClaudeRateLimit(limit, `body.rate_limits.${key}`, errors);
      }
    }
  }
  return errors.length === 0
    ? { valid: true, value: value as unknown as ClaudeIngestBody, errors }
    : { valid: false, errors };
}

export function validateCodexIngest(value: unknown): ValidationResult<CodexIngestBody> {
  const errors: string[] = [];
  if (!isRecord(value)) return { valid: false, errors: ["body: expected object"] };
  rejectUnknown(value, ["installation_id", "observed_at", "rate_limits"], "body", errors);
  if (typeof value.installation_id !== "string" || !INSTALLATION_ID.test(value.installation_id)) {
    errors.push("body.installation_id: invalid installation ID");
  }
  unixSeconds(value.observed_at, "body.observed_at", errors);
  if (!isRecord(value.rate_limits)) {
    errors.push("body.rate_limits: expected object");
  } else {
    rejectUnknown(value.rate_limits, ["rateLimitsByLimitId"], "body.rate_limits", errors);
    if (!isRecord(value.rate_limits.rateLimitsByLimitId)) {
      errors.push("body.rate_limits.rateLimitsByLimitId: expected object");
    } else {
      for (const [limitId, bucket] of Object.entries(value.rate_limits.rateLimitsByLimitId)) {
        if (!LIMIT_ID.test(limitId)) errors.push(`body.rate_limits.rateLimitsByLimitId.${limitId}: invalid limit ID`);
        validateCodexBucket(bucket, `body.rate_limits.rateLimitsByLimitId.${limitId}`, errors);
      }
    }
  }
  return errors.length === 0
    ? { valid: true, value: value as unknown as CodexIngestBody, errors }
    : { valid: false, errors };
}

export function validateTokenUsageIngest(
  provider: Provider,
  value: unknown,
): ValidationResult<TokenUsageIngestBody> {
  const errors: string[] = [];
  if (!isRecord(value)) return { valid: false, errors: ["body: expected object"] };
  const fields = [
    "installation_id",
    "observed_at",
    "today_tokens",
    "total_tokens",
    "today_cost_usd",
    "total_cost_usd",
    "cost_label",
    "source",
  ] as const;
  rejectUnknown(value, fields, "body", errors);
  for (const field of fields) {
    if (!(field in value)) errors.push(`body.${field}: required`);
  }
  if (typeof value.installation_id !== "string" || !INSTALLATION_ID.test(value.installation_id)) {
    errors.push("body.installation_id: invalid installation ID");
  }
  unixSeconds(value.observed_at, "body.observed_at", errors);
  for (const field of ["today_tokens", "total_tokens"] as const) {
    if (!Number.isSafeInteger(value[field]) || (value[field] as number) < 0) {
      errors.push(`body.${field}: expected a non-negative safe integer`);
    }
  }
  nonNegativeNumber(value.today_cost_usd, "body.today_cost_usd", errors);
  nonNegativeNumber(value.total_cost_usd, "body.total_cost_usd", errors);
  if (value.cost_label !== "标准 API 等价费用") {
    errors.push("body.cost_label: unsupported cost label");
  }
  const expectedSource = provider === "claude" ? "ccusage" : "ccusage_codex";
  if (value.source !== expectedSource) {
    errors.push(`body.source: expected ${expectedSource}`);
  }
  if (
    Number.isSafeInteger(value.today_tokens) &&
    Number.isSafeInteger(value.total_tokens) &&
    (value.today_tokens as number) > (value.total_tokens as number)
  ) {
    errors.push("body.today_tokens: cannot exceed total_tokens");
  }
  if (
    typeof value.today_cost_usd === "number" &&
    typeof value.total_cost_usd === "number" &&
    value.today_cost_usd > value.total_cost_usd
  ) {
    errors.push("body.today_cost_usd: cannot exceed total_cost_usd");
  }
  return errors.length === 0
    ? { valid: true, value: value as unknown as TokenUsageIngestBody, errors }
    : { valid: false, errors };
}
