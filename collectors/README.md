# UsageHub Collectors

This is an AI-agent and maintainer reference. Human users should generate one onboarding prompt from the **AI 接入** page at `https://u.80aj.com`; the agent should complete this setup for them.

These Node 22 collectors upload allowlisted Claude and Codex usage snapshots to UsageHub Cloud or a compatible server.

- Claude: quota comes from two documented local sources, both reduced to the official 5-hour and 7-day fields: Claude Code `statusLine` JSON, and the official `claude` binary answering `claude -p "/usage" --output-format json` (works when you only use Claude desktop). The server keeps the snapshot with the newest `observed_at`.
- Codex: the read-only `account/rateLimits/read` app-server call is reduced to official quota fields.
- Local history: optional `ccusage` and `ccusage-codex` data is explicitly labelled as API-equivalent cost, not an official remaining quota.

No prompt, transcript, account email address, file path, cookie, or provider credential is uploaded.

## Configuration

The preferred path uses a 10-minute, single-use enrollment code. It stores the resulting long-lived token in an owner-only local file and never prints the token:

```bash
npm run enroll --workspace @usagehub/collectors -- \
  --code '<single-use-code>' \
  --api-url 'https://u.80aj.com'
```

The default token file is `~/.local/state/usagehub/ingest-token`. The collector loads it automatically. Advanced deployments may instead set the ingest token in a user-level service or secret manager. Do not commit it.

```bash
export USAGEHUB_API_URL=https://u.80aj.com
export USAGEHUB_INGEST_TOKEN='replace-with-a-workspace-ingest-token'
```

Optional environment variables:

```text
USAGEHUB_INSTALLATION_ID=optional-stable-installation-id
USAGEHUB_STATE_DIR=~/.local/state/usagehub
USAGEHUB_INGEST_TOKEN_FILE=~/.local/state/usagehub/ingest-token
USAGEHUB_REQUEST_TIMEOUT_MS=5000
USAGEHUB_QUEUE_MAX_ITEMS=64
USAGEHUB_CLAUDE_USAGE_JSON=/absolute/path/claude-usage.json
USAGEHUB_CODEX_USAGE_JSON=/absolute/path/codex-usage.json
```

The configured JSON files are optional. Without them, the local-history collector calls the relevant `ccusage` command directly. The code never assumes a machine-specific source path.

Claude pricing is refreshed by `ccusage` instead of forcing its bundled offline catalog. Use `ccusage` 20 or newer: the collector requests its per-agent report and imports only the Claude row. If a new Claude model has tokens but no price coverage, the collector rejects the zero-cost result and retains the last valid snapshot.

## Schedule settings file

The background collector reads `~/.config/usagehub/collector.json` (override the path with `USAGEHUB_CONFIG`). If the file is missing, the first run writes this template with owner-only permissions:

```json
{
  "claudeQuotaMinutes": 30,
  "codexQuotaMinutes": 30,
  "tokenUsageMinutes": 30,
  "timezone": "Asia/Shanghai"
}
```

| Field | Meaning | Default |
| --- | --- | --- |
| `claudeQuotaMinutes` | interval for running `claude -p "/usage"` | 30 |
| `codexQuotaMinutes` | interval for the Codex app-server quota read | 30 |
| `tokenUsageMinutes` | interval for the `ccusage` / `ccusage-codex` history (Claude and Codex run as separate channels) | 30 |
| `timezone` | IANA zone that defines "today" for token history; passed to `ccusage --timezone` | `Asia/Shanghai` |

Intervals are whole minutes from 1 to 1440. An invalid field falls back to its default and prints a warning on stderr. Precedence is environment variable > file > default; the optional environment overrides are `USAGEHUB_CLAUDE_USAGE_INTERVAL_MS` (milliseconds) and `USAGEHUB_TIMEZONE`. The file is re-read at least once a minute, so edits apply at the next scheduled run without a restart.

Each channel (`claude_quota`, `codex_quota`, `claude_token_usage`, `codex_token_usage`) is scheduled and retried independently. After a failure the channel retries after min(interval, 5 / 15 / 30 minutes) for the first / second / later consecutive failure; other channels are not affected. A failed channel uploads nothing, so the dashboard ages that snapshot into "stale" instead of showing a guess. Failure reasons (never credentials) go to stderr and to `lastError` in `$USAGEHUB_STATE_DIR/collector-health.json`, which also records `observedAt` / `uploadedAt` per channel.

### Claude `/usage` probe

The collector locates `claude` by absolute path (`USAGEHUB_CLAUDE_BIN`, then `command -v claude`, `/opt/homebrew/bin`, `~/.local/bin`, `~/.claude/local`) and runs, with a 90-second timeout and its working directory set to `$USAGEHUB_STATE_DIR/claude-usage-probe`:

```text
claude -p /usage --output-format json --no-session-persistence --safe-mode \
  --setting-sources "" --strict-mcp-config --mcp-config '{"mcpServers":{}}'
```

`/usage` is answered locally and costs no tokens. `--bare` is deliberately not used because it does not read the subscription login. `--safe-mode` and `--setting-sources ""` keep user hooks, plugins and MCP servers out of the probe, and `--no-session-persistence` keeps it out of the session list and `ccusage`. Only the `Current session` (5-hour) and `Current week (all models)` (7-day) lines are used; per-model weekly lines are dropped because the ingest contract has no field for them. If either line, its percentage, or its reset time (`Oct 24 at 11:09pm (America/Los_Angeles)`, also time-only or with a year) cannot be parsed, nothing is uploaded.

### Collect now (debugging)

```bash
npm run collect-now --workspace @usagehub/collectors
npm run collect-now --workspace @usagehub/collectors -- --only claude   # or codex | tokens
```

It runs the selected channels once, uploads, updates the health file, and prints one summary line per channel (no credentials). It needs the same ingest token as the service (token file or `USAGEHUB_INGEST_TOKEN`); without it the records stay queued and the background service delivers them later. It is safe to run while the service is running: uploads share the queue lock and wait for it briefly.

## Request security

Each upload sends `Authorization: Bearer <ingest token>` plus `x-timestamp`, `x-nonce`, `x-content-sha256`, and `x-signature`. The signature is HMAC-SHA256 over the exact canonical string documented in [docs/ingest-api.md](../docs/ingest-api.md). The token is both the bearer credential and HMAC key.

## Run once

```bash
npm install
npm run build --workspace @usagehub/collectors

# Inspect Codex rate limits without uploading.
npm run codex:smoke --workspace @usagehub/collectors

# Upload one Codex sample.
npm run codex:run --workspace @usagehub/collectors -- --once

# Upload optional local history for Claude and Codex.
npm run local-usage --workspace @usagehub/collectors
```

The collector keeps a bounded, owner-only local queue. A record stays queued if the server does not return 2xx, then it is retried by the next run.

## Claude statusLine bridge

Build the repository first, then configure Claude Code's `statusLine` command to run:

```bash
node /absolute/path/to/usagehub-open/collectors/dist/src/cli/claude-statusline.js
```

The bridge preserves the status-line input and output. It queues a sanitized snapshot and starts a detached upload worker unless `USAGEHUB_BRIDGE_DISABLE_WORKER=1` is set.

## Verification

```bash
npm run test --workspace @usagehub/collectors
npm run typecheck --workspace @usagehub/collectors
```
