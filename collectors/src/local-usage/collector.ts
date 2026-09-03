import { execFile } from "node:child_process";
import { mkdirSync, readFileSync, renameSync, statSync, writeFileSync } from "node:fs";
import { join } from "node:path";
import { promisify } from "node:util";
import type { TokenUsagePayload } from "../shared/types.js";

const execFileAsync = promisify(execFile);
const COST_LABEL = "标准 API 等价费用" as const;
const TIME_ZONE = "America/Los_Angeles";

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

function localDate(now: number): string {
  const parts = new Intl.DateTimeFormat("en-CA", {
    timeZone: TIME_ZONE,
    year: "numeric",
    month: "2-digit",
    day: "2-digit",
  }).formatToParts(new Date(now));
  const field = (type: Intl.DateTimeFormatPartTypes): string =>
    parts.find((part) => part.type === type)?.value ?? "";
  return `${field("year")}-${field("month")}-${field("day")}`;
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

function projectDashboardFile(
  provider: LocalProvider,
  value: unknown,
  now: number,
  observedAtMs: number,
): LocalUsageResult {
  if (!isRecord(value) || !isRecord(value.overview) || !Array.isArray(value.overview.heatmap)) {
    throw new Error("usage dashboard JSON shape is invalid");
  }
  const today = localDate(now);
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
  };
  if ((result.today_tokens ?? 0) > (result.total_tokens ?? 0)) throw new Error("today tokens exceed total");
  if ((result.today_cost_usd ?? 0) > (result.total_cost_usd ?? 0)) throw new Error("today cost exceeds total");
  return result;
}

function projectCliOutput(provider: LocalProvider, value: unknown, now: number): LocalUsageResult {
  if (!isRecord(value) || !Array.isArray(value.daily)) throw new Error("ccusage daily output is invalid");
  const today = localDate(now);
  const todayRow = value.daily.find((candidate) =>
    isRecord(candidate) && normalizeDate(candidate.date) === today,
  );
  const rows = value.daily.filter(isRecord);
  const tokens = (row: UnknownRecord): number => nonNegativeInteger(row.totalTokens, "totalTokens");
  const cost = (row: UnknownRecord): number => {
    const field = provider === "codex" ? row.costUSD : row.totalCost;
    return nonNegativeMoney(field ?? 0, "cost");
  };
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
    };
  }

  async collect(provider: LocalProvider, force = false): Promise<LocalUsageResult> {
    const cached = this.#readCache(provider);
    const now = this.#options.now();
    if (!force && cached && now - cached.cachedAt < this.#options.ttlMs) {
      return { ...cached.result, cached: true };
    }

    try {
      const sourcePath = this.#options.sourceFiles[provider];
      if (!sourcePath) throw new Error("no configured usage JSON source");
      const source = this.#options.sourceReader(sourcePath);
      const result = projectDashboardFile(provider, JSON.parse(source.text) as unknown, now, source.modifiedAtMs);
      this.#writeCache(provider, { cachedAt: now, result });
      return result;
    } catch {
      const [command, args] = provider === "claude"
        ? ["ccusage", ["daily", "--json", "--offline", "--no-color"]]
        : ["ccusage-codex", ["daily", "--json", "--offline", "--noColor", "--locale", "en-US", "--timezone", TIME_ZONE]];
      try {
        const stdout = await this.#options.runner(command, args, this.#options.timeoutMs);
        const result = projectCliOutput(provider, JSON.parse(stdout) as unknown, now);
        this.#writeCache(provider, { cachedAt: now, result });
        return result;
      } catch {
        if (cached) return { ...cached.result, cached: true, stale: true };
        return {
          provider,
          source: sourceFor(provider),
          quota_semantics: "not_official_remaining_quota",
          status: "unavailable",
          observed_at: new Date(now).toISOString(),
          cost_label: COST_LABEL,
        };
      }
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
