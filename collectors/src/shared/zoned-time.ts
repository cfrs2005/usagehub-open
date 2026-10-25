export type ZonedParts = { year: number; month: number; day: number; hour: number; minute: number };

export function isValidTimeZone(value: string): boolean {
  if (typeof value !== "string" || value.length === 0 || value.length > 64) return false;
  try {
    new Intl.DateTimeFormat("en-US", { timeZone: value });
    return true;
  } catch {
    return false;
  }
}

export function zonedParts(epochMs: number, timeZone: string): ZonedParts {
  const parts = new Intl.DateTimeFormat("en-US", {
    timeZone,
    hourCycle: "h23",
    year: "numeric",
    month: "2-digit",
    day: "2-digit",
    hour: "2-digit",
    minute: "2-digit",
  }).formatToParts(new Date(epochMs));
  const field = (type: Intl.DateTimeFormatPartTypes): number => Number(parts.find((part) => part.type === type)?.value);
  return { year: field("year"), month: field("month"), day: field("day"), hour: field("hour"), minute: field("minute") };
}

/** Calendar date (YYYY-MM-DD) of `epochMs` in `timeZone`. */
export function zonedDate(epochMs: number, timeZone: string): string {
  const { year, month, day } = zonedParts(epochMs, timeZone);
  return `${String(year).padStart(4, "0")}-${String(month).padStart(2, "0")}-${String(day).padStart(2, "0")}`;
}

function offsetMs(epochMs: number, timeZone: string): number {
  const p = zonedParts(epochMs, timeZone);
  const asUtc = Date.UTC(p.year, p.month - 1, p.day, p.hour, p.minute);
  return asUtc - Math.floor(epochMs / 60_000) * 60_000;
}

/** Epoch milliseconds of a wall-clock time in `timeZone`; null when the wall time does not exist. */
export function zonedWallTimeToEpochMs(wall: ZonedParts, timeZone: string): number | null {
  const guess = Date.UTC(wall.year, wall.month - 1, wall.day, wall.hour, wall.minute);
  let epoch = guess - offsetMs(guess, timeZone);
  epoch = guess - offsetMs(epoch, timeZone);
  const check = zonedParts(epoch, timeZone);
  if (
    check.year !== wall.year || check.month !== wall.month || check.day !== wall.day ||
    check.hour !== wall.hour || check.minute !== wall.minute
  ) return null;
  return epoch;
}
