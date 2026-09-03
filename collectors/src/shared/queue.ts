import { randomUUID } from "node:crypto";
import {
  mkdirSync,
  openSync,
  closeSync,
  readFileSync,
  readdirSync,
  renameSync,
  rmSync,
  statSync,
  writeFileSync,
} from "node:fs";
import { join } from "node:path";
import type { QueueRecord } from "./types.js";

function queueDirectory(stateDir: string): string {
  return join(stateDir, "queue");
}

function queueFiles(stateDir: string): string[] {
  const directory = queueDirectory(stateDir);
  try {
    return readdirSync(directory)
      .filter((name) => name.endsWith(".json"))
      .sort();
  } catch (error) {
    if ((error as NodeJS.ErrnoException).code === "ENOENT") return [];
    throw error;
  }
}

export function enqueue(stateDir: string, record: QueueRecord, maxItems = 64, now = Date.now()): string {
  const directory = queueDirectory(stateDir);
  mkdirSync(directory, { recursive: true, mode: 0o700 });
  const fileName = `${String(now).padStart(13, "0")}-${randomUUID()}.json`;
  const finalPath = join(directory, fileName);
  const temporaryPath = `${finalPath}.tmp`;
  writeFileSync(temporaryPath, JSON.stringify(record), { encoding: "utf8", mode: 0o600, flag: "wx" });
  renameSync(temporaryPath, finalPath);

  const files = queueFiles(stateDir);
  for (const stale of files.slice(0, Math.max(0, files.length - maxItems))) {
    rmSync(join(directory, stale), { force: true });
  }
  return finalPath;
}

export function listPending(stateDir: string): string[] {
  return queueFiles(stateDir).map((name) => join(queueDirectory(stateDir), name));
}

export function readRecord(path: string): QueueRecord {
  const parsed: unknown = JSON.parse(readFileSync(path, "utf8"));
  if (!parsed || typeof parsed !== "object") throw new Error("invalid queue record");
  const candidate = parsed as Partial<QueueRecord>;
  if (
    (candidate.provider !== "claude" && candidate.provider !== "codex") ||
    (candidate.kind !== undefined && candidate.kind !== "quota" && candidate.kind !== "token_usage") ||
    !candidate.payload
  ) {
    throw new Error("invalid queue record");
  }
  return candidate as QueueRecord;
}

export function removeRecord(path: string): void {
  rmSync(path, { force: true });
}

export function tryAcquireWorkerLock(stateDir: string, staleAfterMs = 60_000): (() => void) | null {
  mkdirSync(stateDir, { recursive: true, mode: 0o700 });
  const lock = join(stateDir, "upload-worker.lock");
  try {
    const fd = openSync(lock, "wx", 0o600);
    writeFileSync(fd, `${process.pid}\n`, "utf8");
    closeSync(fd);
  } catch (error) {
    if ((error as NodeJS.ErrnoException).code !== "EEXIST") throw error;
    try {
      if (Date.now() - statSync(lock).mtimeMs <= staleAfterMs) return null;
      rmSync(lock, { force: true });
      return tryAcquireWorkerLock(stateDir, staleAfterMs);
    } catch {
      return null;
    }
  }
  return () => rmSync(lock, { force: true });
}
