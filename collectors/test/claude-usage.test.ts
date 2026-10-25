import assert from "node:assert/strict";
import { mkdtempSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import test from "node:test";
import { CLAUDE_USAGE_ARGS, extractResultText, probeClaudeUsage } from "../src/claude/usage-probe.js";
import { parseClaudeUsage, parseClaudeUsageText, parseResetText } from "../src/claude/usage-text.js";

const LA = "America/Los_Angeles";
// Real `claude -p "/usage"` result text (header lines only), observed 2026-10-24 ~21:00 PDT.
const SAMPLE = [
  "You are currently using your subscription to power your Claude Code usage",
  "",
  "Current session: 65% used · resets Oct 24 at 11:09pm (America/Los_Angeles)",
  "Current week (all models): 50% used · resets Oct 26 at 9:59pm (America/Los_Angeles)",
  "Current week (Fable): 62% used · resets Oct 26 at 10pm (America/Los_Angeles)",
  "",
  "What's contributing to your limits usage?",
].join("\n");
const NOW = Date.parse("2026-10-25T04:00:00Z"); // Oct 24 21:00 PDT
const sec = (iso: string): number => Date.parse(iso) / 1_000;

test("parses the real /usage sample into 5h and 7d windows", () => {
  const parsed = parseClaudeUsageText(SAMPLE, NOW);
  assert.ok(parsed);
  assert.deepEqual(parsed.five_hour, { used_percentage: 65, resets_at: sec("2026-10-25T06:09:00Z"), durationMinutes: 300 });
  assert.deepEqual(parsed.seven_day, { used_percentage: 50, resets_at: sec("2026-10-27T04:59:00Z"), durationMinutes: 10080 });
});

test("tolerates case, spacing and whole-hour resets", () => {
  const text = "current session : 7.5 % used - Resets Oct 24 at 11 PM (America/Los_Angeles)\nCurrent Week (All Models): 100% used · resets October 26 at 10pm (America/Los_Angeles)";
  const parsed = parseClaudeUsageText(text, NOW);
  assert.ok(parsed);
  assert.equal(parsed.five_hour?.used_percentage, 7.5);
  assert.equal(parsed.five_hour?.resets_at, sec("2026-10-25T06:00:00Z"));
  assert.equal(parsed.seven_day.resets_at, sec("2026-10-27T05:00:00Z"));
});

test("rejects changed wording, missing items and per-model-only output", () => {
  assert.equal(parseClaudeUsageText(SAMPLE.replace("Current session: 65% used", "Current session — 65% used"), NOW), null);
  assert.equal(parseClaudeUsageText(SAMPLE.replace("% used", "% consumed"), NOW), null);
  assert.equal(parseClaudeUsageText(SAMPLE.replace(/^Current week \(all models\).*$/m, ""), NOW), null);
  assert.equal(parseClaudeUsageText(SAMPLE.replace("Current session: 65% used", "Current session: used"), NOW), null);
  assert.equal(parseClaudeUsageText(SAMPLE.replace("65% used · resets Oct 24 at 11:09pm (America/Los_Angeles)", "65% used · paused"), NOW), null, "unknown trailer");
  assert.equal(parseClaudeUsageText(SAMPLE.replace("50% used · resets Oct 26 at 9:59pm", "50% used · resets soon"), NOW), null, "bad weekly reset");
  assert.equal(parseClaudeUsageText(SAMPLE.replace(" (America/Los_Angeles)", ""), NOW), null, "missing zone");
  assert.equal(parseClaudeUsageText(SAMPLE.replace("Oct 24 at 11:09pm (America", "Oct 24 at 11:09pm (Mars"), NOW), null, "unknown zone");
  assert.equal(parseClaudeUsageText(SAMPLE.replace("65% used", "165% used"), NOW), null);
  assert.equal(parseClaudeUsageText(SAMPLE.replace("resets Oct 24 at 11:09pm", "resets in 2 hours"), NOW), null);
  assert.equal(parseClaudeUsageText("", NOW), null);
});

// Synthetic idle shapes, derived from Claude Code 2.1.309's text renderer: the " · resets …" suffix is
// omitted when resets_at is null, and the whole line is skipped when the limit/utilization is null.
const IDLE_NO_RESET = SAMPLE.replace("Current session: 65% used · resets Oct 24 at 11:09pm (America/Los_Angeles)", "Current session: 0% used");
const IDLE_NO_LINE = SAMPLE.replace(/^Current session.*\n/m, "");

test("idle 5h window: no reset time reports the percentage with resets_at null, weekly unchanged", () => {
  const parsed = parseClaudeUsageText(IDLE_NO_RESET, NOW);
  assert.ok(parsed);
  assert.deepEqual(parsed.five_hour, { used_percentage: 0, resets_at: null, durationMinutes: 300 });
  assert.deepEqual(parsed.seven_day, { used_percentage: 50, resets_at: sec("2026-10-27T04:59:00Z"), durationMinutes: 10080 });
});

test("no 5h window: an absent session line omits five_hour instead of inventing 0%", () => {
  const parsed = parseClaudeUsageText(IDLE_NO_LINE, NOW);
  assert.ok(parsed);
  assert.equal(parsed.five_hour, undefined);
  assert.equal(parsed.seven_day.used_percentage, 50);
});

test("unparseable output still fails with a reason", () => {
  assert.deepEqual(parseClaudeUsage(SAMPLE.replace(/^Current week \(all models\).*$/m, ""), NOW), { ok: false, reason: "weekly (all models) line missing" });
  assert.deepEqual(parseClaudeUsage(SAMPLE.replace("50% used", "50% consumed"), NOW), { ok: false, reason: "weekly (all models) line unparseable" });
  assert.deepEqual(parseClaudeUsage(SAMPLE.replace("65% used", "165% used"), NOW), { ok: false, reason: "session line unparseable" });
  assert.deepEqual(parseClaudeUsage("Usage data unavailable", NOW), { ok: false, reason: "weekly (all models) line missing" });
});

test("am/pm edges: 12am is midnight, 12pm is noon, 13pm is invalid", () => {
  assert.equal(parseResetText("Oct 25 at 12am (America/Los_Angeles)", NOW, 300), sec("2026-10-25T07:00:00Z"));
  assert.equal(parseResetText("Oct 25 at 12pm (America/Los_Angeles)", NOW, 300), sec("2026-10-25T19:00:00Z"));
  assert.equal(parseResetText("Oct 25 at 12:30am (America/Los_Angeles)", NOW, 300), sec("2026-10-25T07:30:00Z"));
  assert.equal(parseResetText("Oct 25 at 13pm (America/Los_Angeles)", NOW, 300), null);
  assert.equal(parseResetText("Oct 25 at 23:15 (America/Los_Angeles)", NOW, 300), sec("2026-10-26T06:15:00Z"));
});

test("time-only resets pick the next occurrence; dates outside the window are rejected", () => {
  assert.equal(parseResetText("11:09pm (America/Los_Angeles)", NOW, 300), sec("2026-10-25T06:09:00Z"));
  const late = Date.parse("2026-10-25T06:30:00Z"); // 23:30 PDT
  assert.equal(parseResetText("1am (America/Los_Angeles)", late, 300), sec("2026-10-25T08:00:00Z"));
  assert.equal(parseResetText("Nov 20 at 1am (America/Los_Angeles)", NOW, 10080), null, "too far ahead");
  assert.equal(parseResetText("Oct 20 at 1am (America/Los_Angeles)", NOW, 10080), null, "in the past");
});

test("year rollover: a January reset seen on New Year's Eve lands in the next year", () => {
  const eve = Date.parse("2027-01-01T04:00:00Z"); // Dec 31 2026 20:00 PST
  assert.equal(parseResetText(`Jan 2 at 1am (${LA})`, eve, 10080), sec("2027-01-02T09:00:00Z"));
  assert.equal(parseResetText(`Dec 31 at 11pm (${LA})`, eve, 300), sec("2027-01-01T07:00:00Z"));
  assert.equal(parseResetText(`Jan 2, 2027 at 1am (${LA})`, eve, 10080), sec("2027-01-02T09:00:00Z"));
  assert.equal(parseResetText(`Jan 2, 2026 at 1am (${LA})`, eve, 10080), null);
});

test("DST: a wall time that does not exist is rejected", () => {
  const before = Date.parse("2027-03-14T05:00:00Z"); // Mar 13 2027 21:00 PST, DST starts Mar 14 02:00
  assert.equal(parseResetText(`Mar 14 at 2:30am (${LA})`, before, 300), null);
  assert.equal(parseResetText(`Mar 14 at 3:30am (${LA})`, before, 300), sec("2027-03-14T10:30:00Z"));
});

test("extracts the result event from object and array output, rejecting error results", () => {
  const event = { type: "result", subtype: "success", is_error: false, result: SAMPLE, total_cost_usd: 0 };
  assert.equal(extractResultText(JSON.stringify(event)), SAMPLE);
  assert.equal(extractResultText(JSON.stringify([{ type: "system" }, event])), SAMPLE);
  assert.throws(() => extractResultText(JSON.stringify({ ...event, is_error: true })), /error result/);
  assert.throws(() => extractResultText("not json"), /not JSON/);
  assert.throws(() => extractResultText(JSON.stringify([{ type: "system" }])), /no result/);
});

test("probe uses safe, non-persistent arguments and projects only official windows", async () => {
  assert.equal(CLAUDE_USAGE_ARGS.includes("--bare"), false);
  for (const flag of ["--no-session-persistence", "--safe-mode", "--strict-mcp-config"]) assert.ok(CLAUDE_USAGE_ARGS.includes(flag), flag);
  const stateDir = mkdtempSync(join(tmpdir(), "usagehub-claude-probe-"));
  let seenCwd = "";
  try {
    const payload = await probeClaudeUsage({
      stateDir,
      installationId: "install-claude-01",
      binary: "/bin/false",
      now: () => NOW,
      runner: async (_binary, _args, cwd) => {
        seenCwd = cwd;
        return JSON.stringify({ type: "result", subtype: "success", is_error: false, result: SAMPLE });
      },
    });
    assert.equal(seenCwd, join(stateDir, "claude-usage-probe"));
    assert.deepEqual(payload, {
      installation_id: "install-claude-01",
      observed_at: NOW / 1_000,
      rate_limits: {
        five_hour: { used_percentage: 65, resets_at: sec("2026-10-25T06:09:00Z") },
        seven_day: { used_percentage: 50, resets_at: sec("2026-10-27T04:59:00Z") },
      },
    });
    await assert.rejects(
      probeClaudeUsage({ stateDir, installationId: "install-claude-01", binary: "/bin/false", runner: async () => JSON.stringify({ type: "result", result: "Current week (Fable): 62% used" }) }),
      /parseable session and weekly limits \(weekly \(all models\) line missing\)/,
    );
    const idle = (result: string) =>
      probeClaudeUsage({ stateDir, installationId: "install-claude-01", binary: "/bin/false", now: () => NOW, runner: async () => JSON.stringify({ type: "result", result }) });
    assert.deepEqual((await idle(IDLE_NO_RESET)).rate_limits, {
      five_hour: { used_percentage: 0, resets_at: null },
      seven_day: { used_percentage: 50, resets_at: sec("2026-10-27T04:59:00Z") },
    });
    assert.deepEqual((await idle(IDLE_NO_LINE)).rate_limits, { seven_day: { used_percentage: 50, resets_at: sec("2026-10-27T04:59:00Z") } });
  } finally {
    rmSync(stateDir, { recursive: true, force: true });
  }
});
