# UsageHub Collectors

These Node 22 collectors upload allowlisted Claude and Codex usage snapshots to UsageHub Cloud or a compatible server.

- Claude: `statusLine` JSON is reduced to official quota fields.
- Codex: the read-only `account/rateLimits/read` app-server call is reduced to official quota fields.
- Local history: optional `ccusage` and `ccusage-codex` data is explicitly labelled as API-equivalent cost, not an official remaining quota.

No prompt, transcript, account email address, file path, cookie, or provider credential is uploaded.

## Configuration

Set the ingest token in your shell, a user-level service, or a secret manager. Do not commit it.

```bash
export USAGEHUB_API_URL=https://u.80aj.com
export USAGEHUB_INGEST_TOKEN='replace-with-a-workspace-ingest-token'
```

Optional environment variables:

```text
USAGEHUB_INSTALLATION_ID=optional-stable-installation-id
USAGEHUB_STATE_DIR=~/.local/state/usagehub
USAGEHUB_REQUEST_TIMEOUT_MS=5000
USAGEHUB_QUEUE_MAX_ITEMS=64
USAGEHUB_CLAUDE_USAGE_JSON=/absolute/path/claude-usage.json
USAGEHUB_CODEX_USAGE_JSON=/absolute/path/codex-usage.json
```

The configured JSON files are optional. Without them, the local-history collector calls the relevant `ccusage` command directly. The code never assumes a machine-specific source path.

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
