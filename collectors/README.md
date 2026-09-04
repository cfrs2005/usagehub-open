# UsageHub Collectors

This is an AI-agent and maintainer reference. Human users should generate one onboarding prompt from the **AI 接入** page at `https://u.80aj.com`; the agent should complete this setup for them.

These Node 22 collectors upload allowlisted Claude and Codex usage snapshots to UsageHub Cloud or a compatible server.

- Claude: `statusLine` JSON is reduced to official quota fields.
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
