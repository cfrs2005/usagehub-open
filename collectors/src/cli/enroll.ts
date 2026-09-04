#!/usr/bin/env node
import { resolve } from "node:path";
import { loadConfig } from "../shared/config.js";
import { getInstallationId } from "../shared/installation.js";
import { redeemCollectorEnrollment } from "../onboarding/enrollment.js";

function argument(name: string): string | undefined {
  const index = process.argv.indexOf(name);
  return index >= 0 ? process.argv[index + 1] : undefined;
}

async function main(): Promise<void> {
  const config = loadConfig();
  const code = argument("--code");
  if (!code) throw new Error("--code is required");
  const apiUrl = argument("--api-url") ?? config.apiUrl;
  const stateDir = resolve(argument("--state-dir") ?? config.stateDir);
  const installationId = getInstallationId(stateDir, config.installationId);
  const result = await redeemCollectorEnrollment({ apiUrl, code, installationId, stateDir });
  process.stdout.write(`UsageHub collector enrolled. Secret stored at ${result.tokenFile}.\n`);
}

main().catch((error: Error) => {
  process.stderr.write(`${error.message}\n`);
  process.exitCode = 1;
});
