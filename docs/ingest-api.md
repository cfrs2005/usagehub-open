# Ingest API

The public clients use the private UsageHub Cloud API at `https://u.80aj.com` by default. A user may choose another HTTPS-compatible UsageHub server.

## Authentication and replay protection

Set these environment variables before running a collector:

```bash
export USAGEHUB_API_URL=https://u.80aj.com
export USAGEHUB_INGEST_TOKEN='replace-with-a-workspace-ingest-token'
```

For every request, the collector sends:

- `Authorization: Bearer <ingest token>`;
- `x-timestamp`: Unix seconds;
- `x-nonce`: a fresh base64url nonce;
- `x-content-sha256`: SHA-256 hex of the exact JSON request body; and
- `x-signature`: HMAC-SHA256 hex of `timestamp + "\\n" + nonce + "\\n" + installationId + "\\n" + bodyHash`, keyed by the ingest token.

The server must reject a stale timestamp, reused nonce, body-hash mismatch, invalid signature, or a token that is not authorized for the target workspace.

## Endpoints

| Method | Path | Payload |
| --- | --- | --- |
| `POST` | `/v1/ingest/claude` | `claude-ingest.schema.json` |
| `POST` | `/v1/ingest/codex` | `codex-ingest.schema.json` |
| `POST` | `/v1/ingest/claude/token-usage` | `token-usage-ingest.schema.json` |
| `POST` | `/v1/ingest/codex/token-usage` | `token-usage-ingest.schema.json` |

The client accepts only a 2xx response as a completed upload. A failed record remains in its local bounded queue for a later retry.

## Privacy rule

Payloads include a generated installation ID and allowlisted numeric usage snapshots only. They must never contain a prompt, transcript, file path, email address, cookie, access token, or provider credential.
