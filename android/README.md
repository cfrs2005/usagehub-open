# UsageHub Android

UsageHub Android is a native Android 11+ dashboard for a user's Claude and Codex usage data. It has no third-party runtime SDK.

The normal app is a regular launcher application. It uses landscape for the wide usage dashboard and can remain visible over a non-secure lock screen, but it never registers as the device Home app. Screen-on behavior is an explicit setting.

An optional `kiosk` flavor is available for a dedicated wall display. It adds the boot receiver, full-screen mode, and keep-awake behavior, but still never registers as the device Home app. It is never the default build.

## Pairing and privacy

Create a 10-minute pairing code while signed in to `https://u.80aj.com`, then enter it through the visible Settings button. The app exchanges it once for a read-only display token encrypted by Android Keystore. A direct display token is also accepted. The app calls only the pairing, dashboard, and health endpoints over HTTPS.

## Build

The checked-in Gradle wrapper requires Java 17.

```bash
# Normal app
./gradlew testStandardDebugUnitTest lintStandardDebug assembleStandardDebug --no-daemon

# Optional dedicated-display app
./gradlew testKioskDebugUnitTest lintKioskDebug assembleKioskDebug --no-daemon
```

The output APKs are under `app/build/outputs/apk/standard/debug/` and `app/build/outputs/apk/kiosk/debug/`.

Main-branch CI also produces `app-standard-release.apk` with the repository's protected signing key. Install that artifact for stable in-place upgrades; Debug artifacts are not an upgrade channel.
