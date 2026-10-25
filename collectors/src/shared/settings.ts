import { mkdirSync, readFileSync, renameSync, writeFileSync } from "node:fs";
import { homedir } from "node:os";
import { dirname, join, resolve } from "node:path";
import { isValidTimeZone } from "./zoned-time.js";

/** Local collector schedule. Intervals are whole minutes. */
export type CollectorSettings = {
  claudeQuotaMinutes: number;
  codexQuotaMinutes: number;
  tokenUsageMinutes: number;
  timezone: string;
};

export type LoadedSettings = {
  path: string;
  settings: CollectorSettings;
  /** Human-readable problems; invalid values fall back to defaults. */
  warnings: string[];
  createdTemplate: boolean;
};

export const DEFAULT_SETTINGS: Readonly<CollectorSettings> = Object.freeze({
  claudeQuotaMinutes: 30,
  codexQuotaMinutes: 30,
  tokenUsageMinutes: 30,
  timezone: "Asia/Shanghai",
});

export const MIN_INTERVAL_MINUTES = 1;
export const MAX_INTERVAL_MINUTES = 1440;

const INTERVAL_KEYS = ["claudeQuotaMinutes", "codexQuotaMinutes", "tokenUsageMinutes"] as const;

export function settingsPath(env: NodeJS.ProcessEnv = process.env): string {
  return resolve(env.USAGEHUB_CONFIG ?? join(homedir(), ".config", "usagehub", "collector.json"));
}

function validMinutes(value: unknown): value is number {
  return Number.isInteger(value) && (value as number) >= MIN_INTERVAL_MINUTES && (value as number) <= MAX_INTERVAL_MINUTES;
}

function writeTemplate(path: string): void {
  mkdirSync(dirname(path), { recursive: true, mode: 0o700 });
  const temporary = `${path}.${process.pid}.tmp`;
  writeFileSync(temporary, `${JSON.stringify(DEFAULT_SETTINGS, null, 2)}\n`, { encoding: "utf8", mode: 0o600, flag: "wx" });
  renameSync(temporary, path);
}

/**
 * Precedence: environment variable > config file > default.
 * Env overrides: USAGEHUB_CLAUDE_USAGE_INTERVAL_MS (milliseconds, whole minutes after rounding) and USAGEHUB_TIMEZONE.
 */
export function loadSettings(
  env: NodeJS.ProcessEnv = process.env,
  options: { createTemplate?: boolean } = {},
): LoadedSettings {
  const path = settingsPath(env);
  const settings: CollectorSettings = { ...DEFAULT_SETTINGS };
  const warnings: string[] = [];
  let createdTemplate = false;

  let raw: unknown;
  try {
    raw = JSON.parse(readFileSync(path, "utf8"));
  } catch (error) {
    if ((error as NodeJS.ErrnoException).code === "ENOENT") {
      if (options.createTemplate !== false) {
        try {
          writeTemplate(path);
          createdTemplate = true;
        } catch (writeError) {
          warnings.push(`cannot write settings template ${path}: ${(writeError as Error).message}`);
        }
      }
    } else {
      warnings.push(`settings file ${path} is unreadable or not JSON; using defaults`);
    }
  }

  if (raw !== undefined) {
    if (!raw || typeof raw !== "object" || Array.isArray(raw)) {
      warnings.push(`settings file ${path} must contain a JSON object; using defaults`);
    } else {
      const record = raw as Record<string, unknown>;
      for (const key of INTERVAL_KEYS) {
        if (record[key] === undefined) continue;
        if (validMinutes(record[key])) settings[key] = record[key];
        else warnings.push(`${key} must be an integer between ${MIN_INTERVAL_MINUTES} and ${MAX_INTERVAL_MINUTES}; using ${DEFAULT_SETTINGS[key]}`);
      }
      if (record.timezone !== undefined) {
        if (typeof record.timezone === "string" && isValidTimeZone(record.timezone)) settings.timezone = record.timezone;
        else warnings.push(`timezone must be a valid IANA zone; using ${DEFAULT_SETTINGS.timezone}`);
      }
    }
  }

  const intervalMs = env.USAGEHUB_CLAUDE_USAGE_INTERVAL_MS;
  if (intervalMs !== undefined && intervalMs !== "") {
    const minutes = Math.round(Number(intervalMs) / 60_000);
    if (validMinutes(minutes)) settings.claudeQuotaMinutes = minutes;
    else warnings.push(`USAGEHUB_CLAUDE_USAGE_INTERVAL_MS must be between 60000 and 86400000; ignored`);
  }
  const timeZone = env.USAGEHUB_TIMEZONE;
  if (timeZone !== undefined && timeZone !== "") {
    if (isValidTimeZone(timeZone)) settings.timezone = timeZone;
    else warnings.push(`USAGEHUB_TIMEZONE must be a valid IANA zone; ignored`);
  }

  return { path, settings, warnings, createdTemplate };
}
