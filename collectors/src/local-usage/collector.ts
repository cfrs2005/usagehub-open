import { execFile } from "node:child_process";
import { mkdirSync, readFileSync, renameSync, statSync, writeFileSync } from "node:fs";
import { join } from "node:path";
import { promisify } from "node:util";
import type { TokenUsagePayload } from "../shared/types.js";
import { DEFAULT_SETTINGS } from "../shared/settings.js";
import { zonedDate } from "../shared/zoned-time.js";

const execFileAsync = promisify(execFile);
const COST_LABEL = "标准 API 等价费用" as const;

export type LocalProvider = "claude" | "codex";
export type LocalUsageSource = "ccusage" | "ccusage_codex";

export type LocalUsageResult = {
  provider: LocalProvider;
  source: LocalUsageSource;
  quota_semantics: "not_official_remaining_quota";
  status: "ok" | "unavailable";
  observed_at: string;
  today_tokens?: number;
  total_tokens?: number;
  today_cost_usd?: number;
  total_cost_usd?: number;
  cost_label: typeof COST_LABEL;
  cached?: true;
  stale?: true;
  daily_usage?: Array<{ date: string; tokens: number; costUsd: number }>;
  model_usage?: Array<{ model: string; tokens: number; costUsd: number }>;
  /** Local diagnostic only (never uploaded): why a fresh read failed. */
  error?: string;
};

export type CommandRunner = (command: string, args: string[], timeoutMs: number) => Promise<string>;
export type SourceReader = (path: string) => { text: string; modifiedAtMs: number };

export type LocalUsageOptions = {
  stateDir: string;
  ttlMs?: number;
  timeoutMs?: number;
  now?: () => number;
  runner?: CommandRunner;
  sourceFiles?: Partial<Record<LocalProvider, string>>;
  sourceReader?: SourceReader;
  /** IANA zone that defines "today"; defaults to Asia/Shanghai. */
  timeZone?: string;
};

type UnknownRecord = Record<string, unknown>;
type CacheEnvelope = { cachedAt: number; result: LocalUsageResult };

function isRecord(value: unknown): value is UnknownRecord {
  return Boolean(value) && typeof value === "object" && !Array.isArray(value);
}

function nonNegativeInteger(value: unknown, label: string): number {
  const parsed = typeof value === "string" && value.trim() !== "" ? Number(value) : value;
  if (!Number.isSafeInteger(parsed) || (parsed as number) < 0) throw new Error(`${label} is invalid`);
  return parsed as number;
}

function nonNegativeMoney(value: unknown, label: string): number {
  const parsed = typeof value === "string" && value.trim() !== "" ? Number(value) : value;
  if (typeof parsed !== "number" || !Number.isFinite(parsed) || parsed < 0) {
    throw new Error(`${label} is invalid`);
  }
  return Math.round(parsed * 1_000_000) / 1_000_000;
}

function normalizeDate(value: unknown): string | null {
  if (typeof value !== "string") return null;
  if (/^\d{4}-\d{2}-\d{2}$/.test(value)) return value;
  const match = /^([A-Z][a-z]{2}) (\d{2}), (\d{4})$/.exec(value);
  if (!match) return null;
  const months: Record<string, string> = {
    Jan: "01", Feb: "02", Mar: "03", Apr: "04", May: "05", Jun: "06",
    Jul: "07", Aug: "08", Sep: "09", Oct: "10", Nov: "11", Dec: "12",
  };
  const month = months[match[1] ?? ""];
  return month ? `${match[3]}-${month}-${match[2]}` : null;
}

function sourceFor(provider: LocalProvider): LocalUsageSource {
  return provider === "claude" ? "ccusage" : "ccusage_codex";
}

function modelUsage(rows: UnknownRecord[]): Array<{ model: string; tokens: number; costUsd: number }> {
  const totals = new Map<string, { tokens: number; costUsd: number }>();
  for (const row of rows) {
    const groups = row.modelBreakdowns ?? row.models ?? row.model_breakdowns;
    const entries = Array.isArray(groups) ? groups : isRecord(groups) ? Object.entries(groups).map(([model, value]) => ({ model, ...(isRecord(value) ? value : {}) })) : [];
    for (const entry of entries) {
      if (!isRecord(entry)) continue;
      const model = typeof (entry.modelName ?? entry.model ?? entry.name) === "string" ? String(entry.modelName ?? entry.model ?? entry.name) : "";
      if (!model) continue;
      try {
        // ccusage's Claude modelBreakdowns carry no totalTokens; the row total counts cache creation and cache reads,
        // so the per-model figure must too or model columns would be ~1% of the provider total.
        const tokens = entry.totalTokens ?? (
          (Number(entry.inputTokens) || 0) + (Number(entry.outputTokens) || 0)
          + (Number(entry.cacheCreationTokens) || 0) + (Number(entry.cacheReadTokens) || 0)
        );
        const cost = entry.costUSD ?? entry.costUsd ?? entry.totalCost ?? entry.cost ?? 0;
        const current = totals.get(model) ?? { tokens: 0, costUsd: 0 };
        current.tokens += nonNegativeInteger(tokens, "model tokens");
        current.costUsd += nonNegativeMoney(cost, "model cost");
        totals.set(model, current);
      } catch { /* Optional model rows must not invalidate provider totals. */ }
    }
  }
  return [...totals.entries()].map(([model, value]) => ({ model, tokens: value.tokens, costUsd: Math.round(value.costUsd * 1_000_000) / 1_000_000 }));
}

/**
 * AGENTS.md: never publish a zero price when token usage is non-zero. A day (or today / the total) with tokens but no
 * cost means the price table does not cover a model yet; the whole read is rejected so the caller keeps the last valid
 * snapshot instead of uploading $0. Applies to both providers.
 */
function requireCostCoverage(result: LocalUsageResult): LocalUsageResult {
  const label = result.provider === "claude" ? "Claude" : "Codex";
  if ((result.today_tokens ?? 0) > 0 && (result.today_cost_usd ?? 0) <= 0) {
    throw new Error(`${label} price coverage is missing for today's models`);
  }
  if ((result.total_tokens ?? 0) > 0 && (result.total_cost_usd ?? 0) <= 0) {
    throw new Error(`${label} price coverage is missing for historical models`);
  }
  const unpriced = (result.daily_usage ?? []).find((day) => day.tokens > 0 && day.costUsd <= 0);
  if (unpriced) throw new Error(`${label} price coverage is missing for ${unpriced.date}`);
  return result;
}

function projectDashboardFile(
  provider: LocalProvider,
  value: unknown,
  now: number,
  observedAtMs: number,
  timeZone: string,
): LocalUsageResult {
  if (!isRecord(value) || !isRecord(value.overview) || !Array.isArray(value.overview.heatmap)) {
    throw new Error("usage dashboard JSON shape is invalid");
  }
  const today = zonedDate(now, timeZone);
  const row = value.overview.heatmap.find((candidate) =>
    isRecord(candidate) && normalizeDate(candidate.date) === today,
  );
  const todayRow = isRecord(row) ? row : null;
  const result: LocalUsageResult = {
    provider,
    source: sourceFor(provider),
    quota_semantics: "not_official_remaining_quota",
    status: "ok",
    observed_at: new Date(observedAtMs).toISOString(),
    today_tokens: todayRow ? nonNegativeInteger(todayRow.value, "today tokens") : 0,
    total_tokens: nonNegativeInteger(value.overview.total_tokens, "total tokens"),
    today_cost_usd: todayRow ? nonNegativeMoney(todayRow.total_usd, "today cost") : 0,
    total_cost_usd: nonNegativeMoney(value.overview.total_usd, "total cost"),
    cost_label: COST_LABEL,
    daily_usage: value.overview.heatmap.filter(isRecord).map((item) => ({ date: normalizeDate(item.date) ?? "", tokens: nonNegativeInteger(item.value, "daily tokens"), costUsd: nonNegativeMoney(item.total_usd, "daily cost") })).filter((item) => item.date),
    model_usage: modelUsage([value.overview, ...value.overview.heatmap.filter(isRecord)]),
  };
  if ((result.today_tokens ?? 0) > (result.total_tokens ?? 0)) throw new Error("today tokens exceed total");
  if ((result.today_cost_usd ?? 0) > (result.total_cost_usd ?? 0)) throw new Error("today cost exceeds total");
  return result;
}

function projectCliOutput(provider: LocalProvider, value: unknown, now: number, timeZone: string): LocalUsageResult {
  if (!isRecord(value) || !Array.isArray(value.daily)) throw new Error("ccusage daily output is invalid");
  const today = zonedDate(now, timeZone);
  const rows = value.daily.filter(isRecord).map((row) => {
    if (provider !== "claude" || !Array.isArray(row.agents)) return row;
    const claude = row.agents.find((candidate) => isRecord(candidate) && candidate.agent === "claude");
    if (!isRecord(claude)) return null;
    return { ...claude, date: row.period ?? row.date };
  }).filter(isRecord);
  const todayRow = rows.find((row) => normalizeDate(row.date ?? row.period) === today);
  const tokens = (row: UnknownRecord): number => nonNegativeInteger(row.totalTokens, "totalTokens");
  const cost = (row: UnknownRecord): number => {
    const field = provider === "codex" ? row.costUSD : row.totalCost;
    return nonNegativeMoney(field ?? 0, "cost");
  };
  const daily_usage = rows.map((row) => ({ date: normalizeDate(row.date ?? row.period), tokens: tokens(row), costUsd: cost(row) })).filter((item): item is { date: string; tokens: number; costUsd: number } => item.date !== null);
  return {
    provider,
    source: sourceFor(provider),
    quota_semantics: "not_official_remaining_quota",
    status: "ok",
    observed_at: new Date(now).toISOString(),
    today_tokens: isRecord(todayRow) ? tokens(todayRow) : 0,
    total_tokens: rows.reduce((sum, row) => sum + tokens(row), 0),
    today_cost_usd: isRecord(todayRow) ? cost(todayRow) : 0,
    total_cost_usd: Math.round(rows.reduce((sum, row) => sum + cost(row), 0) * 1_000_000) / 1_000_000,
    cost_label: COST_LABEL,
    daily_usage,
    model_usage: modelUsage(rows),
  };
}

export async function runLocalCommand(command: string, args: string[], timeoutMs: number): Promise<string> {
  const { stdout } = await execFileAsync(command, args, {
    timeout: timeoutMs,
    maxBuffer: 2_097_152,
    encoding: "utf8",
    env: { ...process.env, NO_COLOR: "1" },
  });
  return stdout;
}

export function toTokenUsagePayload(result: LocalUsageResult, installationId: string): TokenUsagePayload | null {
  if (
    result.status !== "ok" ||
    result.today_tokens === undefined ||
    result.total_tokens === undefined ||
    result.today_cost_usd === undefined ||
    result.total_cost_usd === undefined
  ) return null;
  return {
    installation_id: installationId,
    observed_at: Math.floor(Date.parse(result.observed_at) / 1_000),
    today_tokens: result.today_tokens,
    total_tokens: result.total_tokens,
    today_cost_usd: result.today_cost_usd,
    total_cost_usd: result.total_cost_usd,
    cost_label: result.cost_label,
    source: result.source,
    ...(result.daily_usage ? { daily_usage: result.daily_usage } : {}),
    ...(result.model_usage ? { model_usage: result.model_usage } : {}),
  };
}

export class LocalUsageCollector {
  readonly #options: {
    stateDir: string;
    ttlMs: number;
    timeoutMs: number;
    now: () => number;
    runner: CommandRunner;
    sourceFiles: Partial<Record<LocalProvider, string>>;
    sourceReader: SourceReader;
    timeZone: string;
  };

  constructor(options: LocalUsageOptions) {
    this.#options = {
      stateDir: options.stateDir,
      ttlMs: options.ttlMs ?? 30 * 60_000,
      timeoutMs: options.timeoutMs ?? 60_000,
      now: options.now ?? Date.now,
      runner: options.runner ?? runLocalCommand,
      sourceFiles: {
        claude: process.env.USAGEHUB_CLAUDE_USAGE_JSON,
        codex: process.env.USAGEHUB_CODEX_USAGE_JSON,
        ...options.sourceFiles,
      },
      sourceReader: options.sourceReader ?? ((path) => ({
        text: readFileSync(path, "utf8"),
        modifiedAtMs: statSync(path).mtimeMs,
      })),
      timeZone: options.timeZone ?? DEFAULT_SETTINGS.timezone,
    };
  }

  async collect(provider: LocalProvider, force = false): Promise<LocalUsageResult> {
    const cached = this.#readCache(provider);
    const now = this.#options.now();
    if (!force && cached && now - cached.cachedAt < this.#options.ttlMs) {
      return { ...cached.result, cached: true };
    }

    const timeZone = this.#options.timeZone;
    try {
      const sourcePath = this.#options.sourceFiles[provider];
      if (!sourcePath) throw new Error("no configured usage JSON source");
      const source = this.#options.sourceReader(sourcePath);
      const result = requireCostCoverage(projectDashboardFile(provider, JSON.parse(source.text) as unknown, now, source.modifiedAtMs, timeZone));
      this.#writeCache(provider, { cachedAt: now, result });
      return result;
    } catch {
      // Both tools refresh their price table online. The bundled offline Codex table lags new models (it priced
      // gpt-6-astra / gpt-6.1-sol at $0), so it is only a fallback when the online read fails, and its result still
      // has to pass the same zero-price check.
      const command = provider === "claude" ? "ccusage" : "ccusage-codex";
      const attempts = provider === "claude"
        ? [["daily", "--json", "--no-color", "--by-agent", "--timezone", timeZone]]
        : [
            ["daily", "--json", "--noColor", "--locale", "en-US", "--timezone", timeZone],
            ["daily", "--json", "--offline", "--noColor", "--locale", "en-US", "--timezone", timeZone],
          ];
      const failures: string[] = [];
      for (const args of attempts) {
        try {
          const stdout = await this.#options.runner(command, args, this.#options.timeoutMs);
          const result = requireCostCoverage(projectCliOutput(provider, JSON.parse(stdout) as unknown, now, timeZone));
          this.#writeCache(provider, { cachedAt: now, result });
          return result;
        } catch (error) {
          failures.push(`${args.includes("--offline") ? "offline: " : ""}${(error as Error).message}`);
        }
      }
      const reason = `${command}: ${failures.join("; ")}`.replace(/\s+/g, " ").slice(0, 300);
      if (cached) return { ...cached.result, cached: true, stale: true, error: reason };
      return {
        error: reason,
        provider,
        source: sourceFor(provider),
        quota_semantics: "not_official_remaining_quota",
        status: "unavailable",
        observed_at: new Date(now).toISOString(),
        cost_label: COST_LABEL,
      };
    }
  }

  #cacheFile(provider: LocalProvider): string {
    return join(this.#options.stateDir, "local-history", `${provider}.json`);
  }

  #readCache(provider: LocalProvider): CacheEnvelope | null {
    try {
      const parsed = JSON.parse(readFileSync(this.#cacheFile(provider), "utf8")) as CacheEnvelope;
      return parsed.result?.provider === provider && Number.isFinite(parsed.cachedAt) ? parsed : null;
    } catch {
      return null;
    }
  }

  #writeCache(provider: LocalProvider, envelope: CacheEnvelope): void {
    const directory = join(this.#options.stateDir, "local-history");
    mkdirSync(directory, { recursive: true, mode: 0o700 });
    const file = this.#cacheFile(provider);
    const temporary = `${file}.${process.pid}.tmp`;
    writeFileSync(temporary, JSON.stringify(envelope), { encoding: "utf8", mode: 0o600 });
    renameSync(temporary, file);
  }
}
