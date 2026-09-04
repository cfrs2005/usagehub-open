import assert from "node:assert/strict";
import { mkdtempSync, readFileSync, rmSync, statSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import test from "node:test";
import { redeemCollectorEnrollment } from "../src/onboarding/enrollment.js";
import { loadConfig } from "../src/shared/config.js";

test("redeems once without printing the long-lived token and stores it owner-only", async () => {
  const stateDir = mkdtempSync(join(tmpdir(), "usagehub-enrollment-"));
  const token = `uh_ingest_${"a".repeat(32)}`;
  let requestBody = "";
  try {
    const result = await redeemCollectorEnrollment({
      apiUrl: "https://u.80aj.com",
      code: `uh_enroll_${"b".repeat(32)}`,
      installationId: "agent-install-0001",
      stateDir,
    }, async (_input, init) => {
      requestBody = String(init.body);
      return new Response(JSON.stringify({ token, expiresAt: "2027-09-04T00:00:00.000Z" }), { status: 200, headers: { "content-type": "application/json" } });
    });
    assert.deepEqual(JSON.parse(requestBody), { code: `uh_enroll_${"b".repeat(32)}`, installationId: "agent-install-0001" });
    assert.equal(result.tokenFile, join(stateDir, "ingest-token"));
    assert.equal(readFileSync(result.tokenFile, "utf8").trim(), token);
    if (process.platform !== "win32") assert.equal(statSync(result.tokenFile).mode & 0o777, 0o600);
    assert.equal(loadConfig({ USAGEHUB_STATE_DIR: stateDir }).ingestToken, token);
  } finally {
    rmSync(stateDir, { recursive: true, force: true });
  }
});

test("does not create a token file when enrollment fails", async () => {
  const stateDir = mkdtempSync(join(tmpdir(), "usagehub-enrollment-failed-"));
  try {
    await assert.rejects(redeemCollectorEnrollment({ apiUrl: "https://u.80aj.com", code: `uh_enroll_${"c".repeat(32)}`, installationId: "agent-install-0002", stateDir }, async () => new Response(JSON.stringify({ error: "expired" }), { status: 401 })));
    assert.equal(loadConfig({ USAGEHUB_STATE_DIR: stateDir }).ingestToken, "");
  } finally {
    rmSync(stateDir, { recursive: true, force: true });
  }
});
