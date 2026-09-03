import { spawn } from "node:child_process";
import { fileURLToPath } from "node:url";
import { loadConfig } from "../shared/config.js";
import { getInstallationId } from "../shared/installation.js";
import { enqueue } from "../shared/queue.js";
import { projectClaudeStatusLine } from "./project.js";

export type BridgeOptions = {
  env?: NodeJS.ProcessEnv;
  observedAt?: number;
  startWorker?: boolean;
};

export function queueClaudeSample(rawInput: Buffer, options: BridgeOptions = {}): boolean {
  const env = options.env ?? process.env;
  let parsed: unknown;
  try {
    parsed = JSON.parse(rawInput.toString("utf8"));
  } catch {
    return false;
  }

  const config = loadConfig(env);
  const installationId = getInstallationId(config.stateDir, config.installationId);
  const payload = projectClaudeStatusLine(parsed, installationId, options.observedAt);
  if (!payload) return false;
  enqueue(config.stateDir, { provider: "claude", payload }, config.maxQueueItems);

  if (options.startWorker !== false && env.USAGEHUB_BRIDGE_DISABLE_WORKER !== "1") {
    const workerPath = fileURLToPath(new URL("../cli/upload-worker.js", import.meta.url));
    const child = spawn(process.execPath, [workerPath], {
      detached: true,
      stdio: "ignore",
      env,
    });
    child.unref();
  }
  return true;
}
