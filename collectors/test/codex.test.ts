import assert from "node:assert/strict";
import test from "node:test";
import { CodexAppServerClient, CODEX_READ_METHODS } from "../src/codex/app-server.js";
import { projectCodexRateLimits } from "../src/codex/project.js";

const installationId = "install_codex_123456";

test("Codex projection dynamically preserves all buckets and drops account metadata", () => {
  const marker = "DO_NOT_UPLOAD_CREDITS_OR_PLAN";
  const projected = projectCodexRateLimits(
    {
      rateLimitsByLimitId: {
        codex: {
          primary: { usedPercent: 46, windowDurationMins: 10_080, resetsAt: 1_788_751_217 },
          secondary: null,
          credits: { marker },
          planType: marker,
        },
        newly_added_bucket: {
          primary: { usedPercent: 0, windowDurationMins: 300, resetsAt: 1_788_385_426 },
          secondary: { usedPercent: 12, windowDurationMins: 10_080, resetsAt: 1_788_972_226 },
          account: marker,
        },
      },
      rateLimitResetCredits: { marker },
    },
    installationId,
    1_788_372_000,
  );
  assert.deepEqual(projected, {
    installation_id: installationId,
    observed_at: 1_788_372_000,
    rate_limits: {
      rateLimitsByLimitId: {
        codex: {
          primary: { usedPercent: 46, windowDurationMins: 10_080, resetsAt: 1_788_751_217 },
          secondary: null,
        },
        newly_added_bucket: {
          primary: { usedPercent: 0, windowDurationMins: 300, resetsAt: 1_788_385_426 },
          secondary: { usedPercent: 12, windowDurationMins: 10_080, resetsAt: 1_788_972_226 },
        },
      },
    },
  });
  assert.equal(JSON.stringify(projected).includes(marker), false);
});

test("Codex client completes initialize/initialized and three read-only requests", async () => {
  const fakeServer = String.raw`
    const readline = require("node:readline");
    let initialized = false;
    let reads = 0;
    const input = readline.createInterface({ input: process.stdin });
    input.on("line", (line) => {
      const message = JSON.parse(line);
      if (message.method === "initialize") {
        process.stdout.write(JSON.stringify({ id: message.id, result: { userAgent: "fake" } }) + "\n");
      } else if (message.method === "initialized") {
        initialized = true;
      } else if (message.method === "account/rateLimits/read" && initialized) {
        reads += 1;
        process.stdout.write(JSON.stringify({
          id: message.id,
          result: { rateLimitsByLimitId: { ["bucket_" + reads]: {
            primary: { usedPercent: reads, windowDurationMins: 300, resetsAt: 2000 + reads },
            secondary: null
          } } }
        }) + "\n");
      } else if (message.id) {
        process.stdout.write(JSON.stringify({ id: message.id, error: { code: -32601 } }) + "\n");
      }
    });
  `;
  const client = new CodexAppServerClient({
    command: process.execPath,
    args: ["-e", fakeServer],
    requestTimeoutMs: 2_000,
  });
  try {
    await client.start();
    for (let index = 1; index <= 3; index += 1) {
      const result = await client.readRateLimits() as { rateLimitsByLimitId: Record<string, unknown> };
      assert.deepEqual(Object.keys(result.rateLimitsByLimitId), [`bucket_${index}`]);
    }
  } finally {
    await client.close();
  }
});

test("Codex account RPC allowlist contains only the official quota read", () => {
  assert.deepEqual([...CODEX_READ_METHODS], ["account/rateLimits/read"]);
});

test("Codex projection rejects missing and invalid bucket maps", () => {
  assert.equal(projectCodexRateLimits({}, installationId), null);
  assert.equal(projectCodexRateLimits({ rateLimitsByLimitId: {} }, installationId), null);
  assert.equal(
    projectCodexRateLimits({ rateLimitsByLimitId: { broken: { primary: { usedPercent: "5" } } } }, installationId),
    null,
  );
});
