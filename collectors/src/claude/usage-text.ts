import type { ClaudeWindow } from "../shared/types.js";
import { isValidTimeZone, zonedParts, zonedWallTimeToEpochMs } from "../shared/zoned-time.js";

export const FIVE_HOUR_MINUTES = 300;
export const SEVEN_DAY_MINUTES = 10_080;

export type ParsedClaudeUsage = {
  five_hour: ClaudeWindow & { durationMinutes: typeof FIVE_HOUR_MINUTES };
  seven_day: ClaudeWindow & { durationMinutes: typeof SEVEN_DAY_MINUTES };
};

const MONTHS = ["jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec"];
/** A reset may be reported at most this far in the past (display rounding / clock skew). */
const PAST_SLACK_MS = 10 * 60_000;

const RESET_PATTERN = new RegExp(
  "^(?:(?<month>[A-Za-z]{3,9})\\.?\\s+(?<day>\\d{1,2})(?:,?\\s+(?<year>\\d{4}))?(?:\\s+at\\s+|,\\s*|\\s+))?" +
    "(?<hour>\\d{1,2})(?::(?<minute>\\d{2}))?\\s*(?<meridiem>am|pm)?\\s*\\((?<zone>[A-Za-z][A-Za-z0-9_+\\-/]{1,63})\\)\\s*$",
  "i",
);

function monthIndex(name: string): number | null {
  const lower = name.toLowerCase();
  const index = MONTHS.indexOf(lower.slice(0, 3));
  if (index < 0) return null;
  // Accept "Oct" and "October", reject "Octxyz".
  const full = new Intl.DateTimeFormat("en-US", { month: "long", timeZone: "UTC" })
    .format(new Date(Date.UTC(2000, index, 1)))
    .toLowerCase();
  return lower.length === 3 || full === lower ? index + 1 : null;
}

function hour24(hour: number, meridiem: string | undefined): number | null {
  if (!meridiem) return hour >= 0 && hour <= 23 ? hour : null;
  if (hour < 1 || hour > 12) return null;
  const pm = meridiem.toLowerCase() === "pm";
  return (hour % 12) + (pm ? 12 : 0);
}

/**
 * Converts a /usage reset phrase such as "Oct 24 at 11:09pm (America/Los_Angeles)" to epoch seconds.
 * Missing year: the occurrence closest to now. Missing date: the next occurrence of that time.
 * Returns null unless the result lies in [now - slack, now + window + 1 day].
 */
export function parseResetText(text: string, nowMs: number, windowMinutes: number): number | null {
  const match = RESET_PATTERN.exec(text.trim());
  if (!match?.groups) return null;
  const { month, day, year, hour, minute, meridiem, zone } = match.groups;
  if (!zone || !isValidTimeZone(zone)) return null;
  const h = hour24(Number(hour), meridiem);
  const m = minute === undefined ? 0 : Number(minute);
  if (h === null || m > 59) return null;

  const now = zonedParts(nowMs, zone);
  const candidates: number[] = [];
  if (month !== undefined) {
    const mo = monthIndex(month);
    const d = Number(day);
    if (mo === null || d < 1 || d > 31) return null;
    const years = year !== undefined ? [Number(year)] : [now.year - 1, now.year, now.year + 1];
    for (const y of years) {
      const epoch = zonedWallTimeToEpochMs({ year: y, month: mo, day: d, hour: h, minute: m }, zone);
      if (epoch !== null) candidates.push(epoch);
    }
  } else {
    for (const offsetDays of [0, 1]) {
      const base = new Date(Date.UTC(now.year, now.month - 1, now.day + offsetDays));
      const epoch = zonedWallTimeToEpochMs(
        { year: base.getUTCFullYear(), month: base.getUTCMonth() + 1, day: base.getUTCDate(), hour: h, minute: m },
        zone,
      );
      if (epoch !== null) candidates.push(epoch);
    }
  }
  const earliest = nowMs - PAST_SLACK_MS;
  const latest = nowMs + (windowMinutes + 24 * 60) * 60_000;
  const valid = candidates.filter((epoch) => epoch >= earliest && epoch <= latest).sort((a, b) => a - b);
  return valid.length > 0 ? Math.floor(valid[0] / 1_000) : null;
}

function findLine(text: string, label: RegExp): { percent: number; reset: string } | null {
  for (const rawLine of text.split(/\r?\n/)) {
    const line = rawLine.trim();
    const head = label.exec(line);
    if (!head) continue;
    const rest = line.slice(head[0].length);
    const match = /^\s*:\s*(\d{1,3}(?:\.\d+)?)\s*%\s*used\b.*?\bresets?\s+(.+)$/i.exec(rest);
    if (!match) return null;
    const percent = Number(match[1]);
    if (!Number.isFinite(percent) || percent < 0 || percent > 100) return null;
    return { percent, reset: match[2] };
  }
  return null;
}

/**
 * Strictly parses the text body of `claude -p "/usage"`. Both the session (5h) and the all-model weekly
 * (7d) lines must be present and fully parseable, otherwise null. Per-model weekly lines are ignored
 * because the ingest contract has no field for them.
 */
export function parseClaudeUsageText(text: string, nowMs: number): ParsedClaudeUsage | null {
  if (typeof text !== "string" || text.length === 0 || text.length > 65_536) return null;
  const session = findLine(text, /^current session\b/i);
  const week = findLine(text, /^current week\s*\(\s*all models\s*\)/i);
  if (!session || !week) return null;
  const sessionReset = parseResetText(session.reset, nowMs, FIVE_HOUR_MINUTES);
  const weekReset = parseResetText(week.reset, nowMs, SEVEN_DAY_MINUTES);
  if (sessionReset === null || weekReset === null) return null;
  return {
    five_hour: { used_percentage: session.percent, resets_at: sessionReset, durationMinutes: FIVE_HOUR_MINUTES },
    seven_day: { used_percentage: week.percent, resets_at: weekReset, durationMinutes: SEVEN_DAY_MINUTES },
  };
}
