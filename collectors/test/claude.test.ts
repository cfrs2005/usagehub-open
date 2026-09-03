import assert from "node:assert/strict";
import { mkdtempSync, readFileSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import test from "node:test";
import { queueClaudeSample } from "../src/claude/bridge.js";
import { runDelegate } from "../src/claude/delegate.js";
import { projectClaudeStatusLine } from "../src/claude/project.js";
import { enqueue, listPending, readRecord } from "../src/shared/queue.js";

const installationId = "install_test_123456";

test("Claude projection includes only approved fields", () => {
  const marker = "FORBIDDEN_MARKER_19fa";
  const payload = projectClaudeStatusLine(
    {
      claude_version: "2.1.0",
      cwd: `/private/${marker}`,
      transcript_path: marker,
      prompt: marker,
      rate_limits: {
        five_hour: { used_percentage: 63, resets_at: 1_788_356_400, prompt: marker },
        seven_day: { used_percentage: 48, resets_at: 1_788_681_600, email: marker },
        unknown: { secret: marker },
      },
    },
    installationId,
    1_788_371_370,
  );
  assert.deepEqual(payload, {
    installation_id: installationId,
    observed_at: 1_788_371_370,
    claude_version: "2.1.0",
    rate_limits: {
      five_hour: { used_percentage: 63, resets_at: 1_788_356_400 },
      seven_day: { used_percentage: 48, resets_at: 1_788_681_600 },
    },
  });
  assert.equal(JSON.stringify(payload).includes(marker), false);
});

test("Claude projection skips missing or invalid rate limits", () => {
  assert.equal(projectClaudeStatusLine({ cwd: "/secret" }, installationId), null);
  assert.equal(
    projectClaudeStatusLine({ rate_limits: { five_hour: { used_percentage: 12 } } }, installationId),
    null,
  );
});

test("Claude bridge writes a strict finite queue", () => {
  const stateDir = mkdtempSync(join(tmpdir(), "usagehub-claude-"));
  try {
    const marker = "NEVER_QUEUE_THIS";
    const env = {
      ...process.env,
      USAGEHUB_STATE_DIR: stateDir,
      USAGEHUB_INSTALLATION_ID: installationId,
      USAGEHUB_QUEUE_MAX_ITEMS: "2",
    };
    for (let index = 0; index < 3; index += 1) {
      const input = Buffer.from(JSON.stringify({
        cwd: marker,
        transcript_path: marker,
        rate_limits: { five_hour: { used_percentage: index, resets_at: 1_788_356_400 } },
      }));
      assert.equal(queueClaudeSample(input, { env, startWorker: false }), true);
    }
    const pending = listPending(stateDir);
    assert.equal(pending.length, 2);
    const serialized = pending.map((file) => readFileSync(file, "utf8")).join("\n");
    assert.equal(serialized.includes(marker), false);
    assert.equal(readRecord(pending[0]).provider, "claude");
  } finally {
    rmSync(stateDir, { recursive: true, force: true });
  }
});

test("missing Claude rate limits never enter the queue", () => {
  const stateDir = mkdtempSync(join(tmpdir(), "usagehub-claude-empty-"));
  try {
    const queued = queueClaudeSample(Buffer.from(JSON.stringify({ cwd: "/private/work" })), {
      env: {
        ...process.env,
        USAGEHUB_STATE_DIR: stateDir,
        USAGEHUB_INSTALLATION_ID: installationId,
      },
      startWorker: false,
    });
    assert.equal(queued, false);
    assert.equal(listPending(stateDir).length, 0);
  } finally {
    rmSync(stateDir, { recursive: true, force: true });
  }
});

test("existing statusLine output and exit code are preserved", async () => {
  const result = await runDelegate("printf 'legacy-statusline'", Buffer.from('{"cwd":"/private/work"}'));
  assert.equal(result.stdout.toString(), "legacy-statusline");
  assert.equal(result.exitCode, 0);
});

test("queue pruning keeps the newest bounded records", () => {
  const stateDir = mkdtempSync(join(tmpdir(), "usagehub-queue-"));
  try {
    const payload = projectClaudeStatusLine(
      { rate_limits: { five_hour: { used_percentage: 1, resets_at: 2 } } },
      installationId,
    );
    assert.ok(payload);
    enqueue(stateDir, { provider: "claude", payload }, 1, 1);
    enqueue(stateDir, { provider: "claude", payload }, 1, 2);
    assert.equal(listPending(stateDir).length, 1);
  } finally {
    rmSync(stateDir, { recursive: true, force: true });
  }
});
