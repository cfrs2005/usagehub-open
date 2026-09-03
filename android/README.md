# UsageHub Android

UsageHub Android is a native Android 11+ dashboard for a user's Claude and Codex usage data. It has no third-party runtime SDK.

The normal app is a regular launcher application. It uses landscape for the wide usage dashboard, but does not register as the device Home app, launch on boot, show over the lock screen, or keep the display awake.

An optional `kiosk` flavor is available for a dedicated wall display. It adds the Home intent, boot receiver, lock-screen visibility, full-screen mode, and keep-awake behavior. It is never the default build.

## Pairing and privacy

Create a display token while signed in to `https://u.80aj.com`, then enter it in the app's settings. The token is encrypted by a key held in Android Keystore. The app calls only the dashboard and health endpoints over HTTPS.

## Build

The checked-in Gradle wrapper requires Java 17.

```bash
# Normal app
./gradlew testStandardDebugUnitTest lintStandardDebug assembleStandardDebug --no-daemon

# Optional dedicated-display app
./gradlew testKioskDebugUnitTest lintKioskDebug assembleKioskDebug --no-daemon
```

The output APKs are under `app/build/outputs/apk/standard/debug/` and `app/build/outputs/apk/kiosk/debug/`.
