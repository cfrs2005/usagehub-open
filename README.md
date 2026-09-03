# UsageHub Open

UsageHub Open contains the public clients for the UsageHub service:

- an Android dashboard application;
- desktop collectors for official Claude and Codex usage snapshots; and
- strict JSON schemas shared with the private UsageHub Cloud service.

The cloud service, its database, deployment files, and operational data are not part of this repository.

## Data boundary

Collectors send only allowlisted usage fields. They never send prompts, transcripts, account email addresses, working directories, cookies, or provider credentials. The Android application stores its display token in Android Keystore-backed storage.

## Quick start

1. Create an account at `https://u.80aj.com`.
2. Create a collector ingest token in the web application.
3. Set `USAGEHUB_API_URL` and `USAGEHUB_INGEST_TOKEN` in your shell or process manager.
4. Run a collector from `collectors/`.
5. Install the Android APK and pair it using a display token from the web application.

See [collectors/README.md](collectors/README.md) and [android/README.md](android/README.md) for detailed instructions. The network request contract is in [docs/ingest-api.md](docs/ingest-api.md).

## Repository layout

```text
android/     Android client. Standard mode is the default; kiosk mode is optional.
collectors/  Claude, Codex, and local cost collectors.
contracts/   JSON schemas and TypeScript validation utilities.
docs/        Public protocol and contributor documentation.
```

## Development checks

```bash
npm install
npm run check

cd android
./gradlew testStandardDebugUnitTest lintStandardDebug assembleStandardDebug \\
  testKioskDebugUnitTest lintKioskDebug assembleKioskDebug --no-daemon
```

## License

Apache-2.0. See [LICENSE](LICENSE).
