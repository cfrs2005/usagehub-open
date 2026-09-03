#!/usr/bin/env node
import { queueClaudeSample } from "../claude/bridge.js";
import { runDelegate } from "../claude/delegate.js";

function delegateArgument(args: string[]): string {
  const index = args.indexOf("--delegate-base64");
  if (index >= 0 && args[index + 1]) return Buffer.from(args[index + 1], "base64url").toString("utf8");
  return process.env.CLAUDE_STATUSLINE_COMMAND ?? "";
}

async function readStdin(limit = 1_048_576): Promise<Buffer> {
  const chunks: Buffer[] = [];
  let size = 0;
  for await (const chunk of process.stdin) {
    const value = Buffer.from(chunk);
    size += value.length;
    if (size > limit) throw new Error("statusLine input exceeds 1 MiB");
    chunks.push(value);
  }
  return Buffer.concat(chunks);
}

async function main(): Promise<void> {
  const input = await readStdin();
  const delegate = runDelegate(delegateArgument(process.argv.slice(2)), input);
  try {
    queueClaudeSample(input);
  } catch {
    // Collection is best effort. Existing statusLine output always wins.
  }
  const result = await delegate;
  process.stdout.write(result.stdout);
  process.stderr.write(result.stderr);
  process.exitCode = result.exitCode;
}

main().catch(() => {
  process.exitCode = 1;
});
