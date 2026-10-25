#!/usr/bin/env node
import { runChannel } from "../runtime/channels.js";
import { CHANNELS, type Channel } from "../runtime/health.js";
import { loadConfig } from "../shared/config.js";
import { getInstallationId } from "../shared/installation.js";
import { loadSettings } from "../shared/settings.js";

const GROUPS: Record<string, readonly Channel[]> = {
  claude: ["claude_quota"],
  codex: ["codex_quota"],
  tokens: ["claude_token_usage", "codex_token_usage"],
};

function selectChannels(args: string[]): readonly Channel[] {
  const index = args.indexOf("--only");
  if (index < 0) return CHANNELS;
  const value = args[index + 1] ?? "";
  const channels = GROUPS[value];
  if (!channels) throw new Error("--only must be one of: claude, codex, tokens");
  return channels;
}

async function main(): Promise<void> {
  const channels = selectChannels(process.argv.slice(2));
  const loaded = loadSettings();
  for (const warning of loaded.warnings) process.stderr.write(`settings warning: ${warning}\n`);
  const config = loadConfig();
  const installationId = getInstallationId(config.stateDir, config.installationId);
  process.stdout.write(`settings ${loaded.path}: ${JSON.stringify(loaded.settings)}\n`);
  let failed = 0;
  for (const channel of channels) {
    const started = Date.now();
    const { outcome, summary } = await runChannel(channel, { config, settings: loaded.settings, installationId });
    const seconds = ((Date.now() - started) / 1_000).toFixed(1);
    if (outcome.error) failed += 1;
    const status = outcome.error ? `FAIL ${outcome.error}` : `ok uploadedAt=${outcome.uploadedAt}`;
    process.stdout.write(`${channel.padEnd(19)} ${status}${summary ? ` | ${summary}` : ""}${outcome.observedAt ? ` | observedAt=${outcome.observedAt}` : ""} (${seconds}s)\n`);
  }
  process.exitCode = failed > 0 ? 1 : 0;
}

main().catch((error: Error) => {
  process.stderr.write(`${error.message}\n`);
  process.exitCode = 1;
});
