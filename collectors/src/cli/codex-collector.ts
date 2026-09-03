#!/usr/bin/env node
import { collectCodexSamples, runCodexLoop } from "../codex/collector.js";

function numberArgument(args: string[], name: string): number | undefined {
  const index = args.indexOf(name);
  if (index < 0 || !args[index + 1]) return undefined;
  const parsed = Number(args[index + 1]);
  if (!Number.isInteger(parsed) || parsed < 1) throw new Error(`${name} requires a positive integer`);
  return parsed;
}

async function main(): Promise<void> {
  const args = process.argv.slice(2);
  const smokeCount = numberArgument(args, "--smoke-count");
  const noUpload = args.includes("--no-upload");
  if (smokeCount) {
    const samples = await collectCodexSamples(smokeCount, { upload: !noUpload });
    samples.forEach((sample, index) => {
      const count = Object.keys(sample.rate_limits.rateLimitsByLimitId).length;
      process.stdout.write(`sample ${index + 1}: ${count} official buckets\n`);
    });
    return;
  }
  if (args.includes("--once")) {
    const [sample] = await collectCodexSamples(1, { upload: !noUpload });
    process.stdout.write(`${Object.keys(sample.rate_limits.rateLimitsByLimitId).length} official buckets collected\n`);
    return;
  }

  const controller = new AbortController();
  process.once("SIGINT", () => controller.abort());
  process.once("SIGTERM", () => controller.abort());
  await runCodexLoop({ signal: controller.signal });
}

main().catch((error: Error) => {
  process.stderr.write(`${error.message}\n`);
  process.exitCode = 1;
});
