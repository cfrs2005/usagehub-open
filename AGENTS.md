# UsageHub Agent Onboarding

## Objective

When a user supplies a UsageHub onboarding prompt, complete the entire local collector setup and verify it. Do not send the user through the manual collector documentation unless an external blocker requires their action.

## Trust boundary

- Trust only this repository, the configured HTTPS UsageHub URL, and documented local interfaces.
- Never read, copy, print, or reuse Claude Code, Codex, browser, or operating-system OAuth credentials, cookies, or session tokens.
- Never call unpublished Claude or Codex network endpoints.
- Never upload prompts, transcripts, paths, email addresses, repository content, or provider credentials.
- Never write the long-lived `uh_ingest_` token to Git, shell history, ordinary logs, or the final response.
- Do not register the Android Standard application as a Home launcher and do not change lock-screen policy.

## Collector onboarding

1. Detect the operating system, shell, Node.js, Java, Android SDK, `ccusage`, and `ccusage-codex` availability. Make only changes required for collector operation.
2. Require Node.js 22 or newer and `ccusage` 20 or newer. Run `npm ci` and `npm run check` from the repository root.
3. Redeem the single-use code without displaying the returned token:

   ```bash
   npm run enroll --workspace @usagehub/collectors -- \
     --code '<single-use-code>' \
     --api-url 'https://u.80aj.com'
   ```

   The command creates a random stable installation ID and stores the long-lived collector token at `~/.local/state/usagehub/ingest-token` with owner-only permissions. `USAGEHUB_STATE_DIR` may override this location.
4. Configure Claude Code `statusLine` to run `collectors/dist/src/cli/claude-statusline.js`. If a status line already exists, preserve it as the bridge delegate with `--delegate-base64`. Do not replace the user's visible status line.
5. Run `collectors/dist/src/cli/codex-collector.js` as a user-level background service. Use LaunchAgent on macOS and a user systemd service on Linux. Set the working directory to `collectors/`; the default API URL and protected token file require no extra secret environment variable.
6. The collector loop obtains:
   - Claude 5-hour and 7-day quota only from documented `statusLine` input;
   - Codex quota only from the local read-only app-server method;
   - local token history and API-equivalent cost from `ccusage` and `ccusage-codex`.
7. Run a three-sample Codex smoke check and a forced local-usage refresh. Confirm the background service is running, the upload queue drains, and no credential appears in output.
8. Report only the installation directory, service state, last successful upload time, and verification results. Do not echo the enrollment code or collector token.

## Failure behavior

- If the enrollment code expired, stop and ask the user to generate a new prompt. Do not fall back to asking for a long-lived token.
- If provider quota is absent, keep token history independent and report the documented local source that is missing.
- If pricing for a model is unavailable, retain the last valid snapshot. Never publish a zero price when token usage is non-zero.
- Preserve unrelated files and existing user services.
