import { execFile, spawn } from "node:child_process";
import { accessSync, constants, mkdirSync } from "node:fs";
import { homedir } from "node:os";
import { isAbsolute, join } from "node:path";
import type { ClaudePayload, ClaudeWindow } from "../shared/types.js";
import { parseClaudeUsage } from "./usage-text.js";

/**
 * Arguments for the official `claude` binary. `/usage` is answered locally by the CLI (0 tokens).
 * Not `--bare`: bare mode does not read the subscription login.
 * `--safe-mode` disables hooks, plugins, skills and CLAUDE.md while keeping auth;
 * `--no-session-persistence` keeps the probe out of ~/.claude/projects and ccusage.
 */
export const CLAUDE_USAGE_ARGS: readonly string[] = Object.freeze([
  "-p",
  "/usage",
  "--output-format",
  "json",
  "--no-session-persistence",
  "--safe-mode",
  "--setting-sources",
  "",
  "--strict-mcp-config",
  "--mcp-config",
  '{"mcpServers":{}}',
]);

export const CLAUDE_USAGE_TIMEOUT_MS = 90_000;
const MAX_OUTPUT_BYTES = 1_048_576;

function executable(path: string): boolean {
  try {
    accessSync(path, constants.X_OK);
    return true;
  } catch {
    return false;
  }
}

function commandV(env: NodeJS.ProcessEnv): Promise<string | null> {
  return new Promise((resolve) => {
    execFile("/bin/sh", ["-c", "command -v claude"], { env, timeout: 5_000 }, (error, stdout) => {
      const found = String(stdout ?? "").trim().split("\n")[0] ?? "";
      resolve(!error && isAbsolute(found) ? found : null);
    });
  });
}

/** Resolves an absolute path: USAGEHUB_CLAUDE_BIN, `command -v`, Homebrew, ~/.local/bin, ~/.claude/local. */
export async function findClaudeBinary(env: NodeJS.ProcessEnv = process.env, home = homedir()): Promise<string> {
  const override = env.USAGEHUB_CLAUDE_BIN;
  if (override) {
    if (!isAbsolute(override) || !executable(override)) throw new Error("USAGEHUB_CLAUDE_BIN must be an absolute executable path");
    return override;
  }
  const candidates = [
    await commandV(env),
    "/opt/homebrew/bin/claude",
    join(home, ".local", "bin", "claude"),
    join(home, ".claude", "local", "claude"),
  ];
  for (const candidate of candidates) if (candidate && executable(candidate)) return candidate;
  throw new Error("claude binary not found (set USAGEHUB_CLAUDE_BIN)");
}

export type ProbeRunner = (binary: string, args: readonly string[], cwd: string, timeoutMs: number) => Promise<string>;

export const runClaudeProcess: ProbeRunner = (binary, args, cwd, timeoutMs) =>
  new Promise((resolve, reject) => {
    const child = spawn(binary, [...args], {
      cwd,
      stdio: ["ignore", "pipe", "pipe"],
      env: { ...process.env, NO_COLOR: "1" },
    });
    const chunks: Buffer[] = [];
    let size = 0;
    let stderrTail = "";
    let settled = false;
    const finish = (error: Error | null, value?: string): void => {
      if (settled) return;
      settled = true;
      clearTimeout(timer);
      if (error) reject(error);
      else resolve(value ?? "");
    };
    const timer = setTimeout(() => {
      child.kill("SIGKILL");
      finish(new Error(`claude /usage timed out after ${Math.round(timeoutMs / 1_000)}s`));
    }, timeoutMs);
    child.stdout.on("data", (chunk: Buffer) => {
      size += chunk.length;
      if (size > MAX_OUTPUT_BYTES) {
        child.kill("SIGKILL");
        finish(new Error("claude /usage output exceeds 1 MiB"));
        return;
      }
      chunks.push(chunk);
    });
    child.stderr.on("data", (chunk: Buffer) => {
      stderrTail = (stderrTail + chunk.toString("utf8")).slice(-400);
    });
    child.on("error", (error) => finish(new Error(`claude /usage failed to start: ${error.message}`)));
    child.on("close", (code, signal) => {
      if (code === 0) finish(null, Buffer.concat(chunks).toString("utf8"));
      else {
        const detail = stderrTail.replace(/\s+/g, " ").trim().slice(0, 200);
        finish(new Error(`claude /usage exited with ${code ?? signal}${detail ? `: ${detail}` : ""}`));
      }
    });
  });

/** Extracts the `result` text from `--output-format json` (a single object or an event array). */
export function extractResultText(stdout: string): string {
  let parsed: unknown;
  try {
    parsed = JSON.parse(stdout);
  } catch {
    throw new Error("claude /usage output is not JSON");
  }
  const items = Array.isArray(parsed) ? parsed : [parsed];
  const result = [...items].reverse().find(
    (item): item is Record<string, unknown> => Boolean(item) && typeof item === "object" && (item as Record<string, unknown>).type === "result",
  );
  if (!result) throw new Error("claude /usage output has no result event");
  if (result.is_error === true || (result.subtype !== undefined && result.subtype !== "success")) {
    throw new Error(`claude /usage returned an error result (${String(result.subtype ?? "error")})`);
  }
  if (typeof result.result !== "string") throw new Error("claude /usage result is not text");
  return result.result;
}

export type ClaudeUsageProbeOptions = {
  stateDir: string;
  installationId: string;
  env?: NodeJS.ProcessEnv;
  now?: () => number;
  runner?: ProbeRunner;
  binary?: string;
  timeoutMs?: number;
};

/** Runs `claude -p /usage` once and projects it to the Claude ingest payload. Throws with a reason on any failure. */
export async function probeClaudeUsage(options: ClaudeUsageProbeOptions): Promise<ClaudePayload> {
  const binary = options.binary ?? (await findClaudeBinary(options.env));
  const cwd = join(options.stateDir, "claude-usage-probe");
  mkdirSync(cwd, { recursive: true, mode: 0o700 });
  const stdout = await (options.runner ?? runClaudeProcess)(binary, CLAUDE_USAGE_ARGS, cwd, options.timeoutMs ?? CLAUDE_USAGE_TIMEOUT_MS);
  const text = extractResultText(stdout);
  const nowMs = (options.now ?? Date.now)();
  const parsed = parseClaudeUsage(text, nowMs);
  if (!parsed.ok) throw new Error(`claude /usage text did not contain parseable session and weekly limits (${parsed.reason})`);
  const window = (w: ClaudeWindow): ClaudeWindow => ({ used_percentage: w.used_percentage, resets_at: w.resets_at });
  const rate_limits: ClaudePayload["rate_limits"] = {};
  if (parsed.value.five_hour) rate_limits.five_hour = window(parsed.value.five_hour);
  rate_limits.seven_day = window(parsed.value.seven_day);
  return { installation_id: options.installationId, observed_at: Math.floor(nowMs / 1_000), rate_limits };
}
