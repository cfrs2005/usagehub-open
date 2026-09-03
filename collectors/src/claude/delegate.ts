import { spawn } from "node:child_process";

export type DelegateResult = {
  stdout: Buffer;
  stderr: Buffer;
  exitCode: number;
};

export async function runDelegate(command: string, input: Buffer): Promise<DelegateResult> {
  if (command.trim().length === 0) return { stdout: Buffer.alloc(0), stderr: Buffer.alloc(0), exitCode: 0 };
  return new Promise((resolve, reject) => {
    const child = spawn("/bin/zsh", ["-lc", command], {
      stdio: ["pipe", "pipe", "pipe"],
      env: process.env,
    });
    const stdout: Buffer[] = [];
    const stderr: Buffer[] = [];
    child.stdout.on("data", (chunk: Buffer) => stdout.push(chunk));
    child.stderr.on("data", (chunk: Buffer) => stderr.push(chunk));
    child.on("error", reject);
    child.on("close", (code) => {
      resolve({ stdout: Buffer.concat(stdout), stderr: Buffer.concat(stderr), exitCode: code ?? 1 });
    });
    child.stdin.end(input);
  });
}
