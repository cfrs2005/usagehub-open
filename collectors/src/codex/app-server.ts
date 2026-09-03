import { spawn, type ChildProcessWithoutNullStreams } from "node:child_process";
import { createInterface, type Interface } from "node:readline";

type JsonRpcMessage = {
  id?: number;
  method?: string;
  result?: unknown;
  error?: { code?: number; message?: string };
};

type PendingRequest = {
  resolve: (value: unknown) => void;
  reject: (error: Error) => void;
  timeout: NodeJS.Timeout;
};

export const CODEX_READ_METHODS = Object.freeze(["account/rateLimits/read"] as const);

export type CodexClientOptions = {
  command?: string;
  args?: string[];
  env?: NodeJS.ProcessEnv;
  requestTimeoutMs?: number;
};

export class CodexAppServerClient {
  readonly #options: Required<Pick<CodexClientOptions, "command" | "args" | "requestTimeoutMs">> & {
    env: NodeJS.ProcessEnv;
  };
  #child: ChildProcessWithoutNullStreams | null = null;
  #readline: Interface | null = null;
  #pending = new Map<number, PendingRequest>();
  #nextId = 1;
  #started = false;

  constructor(options: CodexClientOptions = {}) {
    this.#options = {
      command: options.command ?? "codex",
      args: options.args ?? ["app-server", "--listen", "stdio://"],
      env: options.env ?? process.env,
      requestTimeoutMs: options.requestTimeoutMs ?? 10_000,
    };
  }

  async start(): Promise<void> {
    if (this.#started) return;
    this.#child = spawn(this.#options.command, this.#options.args, {
      stdio: ["pipe", "pipe", "pipe"],
      env: this.#options.env,
    });
    this.#child.stderr.resume();
    this.#readline = createInterface({ input: this.#child.stdout, crlfDelay: Infinity });
    this.#readline.on("line", (line) => this.#handleLine(line));
    this.#child.on("error", (error) => this.#failAll(new Error(`Codex app-server failed: ${error.message}`)));
    this.#child.on("close", () => this.#failAll(new Error("Codex app-server closed")));

    await this.#protocolRequest("initialize", {
      clientInfo: {
        name: "usagehub",
        title: "UsageHub Collector",
        version: "0.1.0",
      },
    });
    this.#notify("initialized", {});
    this.#started = true;
  }

  async readRateLimits(): Promise<unknown> {
    if (!this.#started) throw new Error("Codex app-server is not initialized");
    return this.#readRequest(CODEX_READ_METHODS[0]);
  }

  async close(): Promise<void> {
    this.#started = false;
    this.#readline?.close();
    this.#readline = null;
    if (this.#child) {
      this.#child.stdin.end();
      this.#child.kill("SIGTERM");
      this.#child = null;
    }
    this.#failAll(new Error("Codex app-server client closed"));
  }

  #readRequest(method: string): Promise<unknown> {
    if (!(CODEX_READ_METHODS as readonly string[]).includes(method)) {
      throw new Error("Codex method is not in the read-only allowlist");
    }
    return this.#request(method, null);
  }

  #protocolRequest(method: "initialize", params: unknown): Promise<unknown> {
    return this.#request(method, params);
  }

  #request(method: string, params: unknown): Promise<unknown> {
    const id = this.#nextId++;
    return new Promise((resolve, reject) => {
      const timeout = setTimeout(() => {
        this.#pending.delete(id);
        reject(new Error(`Codex app-server request timed out: ${method}`));
      }, this.#options.requestTimeoutMs);
      this.#pending.set(id, { resolve, reject, timeout });
      try {
        this.#write({ id, method, params });
      } catch (error) {
        clearTimeout(timeout);
        this.#pending.delete(id);
        reject(error as Error);
      }
    });
  }

  #notify(method: "initialized", params: unknown): void {
    this.#write({ method, params });
  }

  #write(message: unknown): void {
    if (!this.#child?.stdin.writable) throw new Error("Codex app-server stdin is unavailable");
    this.#child.stdin.write(`${JSON.stringify(message)}\n`);
  }

  #handleLine(line: string): void {
    let message: JsonRpcMessage;
    try {
      message = JSON.parse(line) as JsonRpcMessage;
    } catch {
      return;
    }
    if (typeof message.id !== "number") return;
    const pending = this.#pending.get(message.id);
    if (!pending) return;
    clearTimeout(pending.timeout);
    this.#pending.delete(message.id);
    if (message.error) {
      pending.reject(new Error(`Codex app-server RPC error ${message.error.code ?? "unknown"}`));
      return;
    }
    pending.resolve(message.result);
  }

  #failAll(error: Error): void {
    for (const pending of this.#pending.values()) {
      clearTimeout(pending.timeout);
      pending.reject(error);
    }
    this.#pending.clear();
    this.#started = false;
  }
}
