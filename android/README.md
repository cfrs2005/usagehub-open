# UsageHub Android

UsageHub Android is a native Android 11+ dashboard for a user's Claude and Codex usage data. It has no third-party runtime SDK.

The normal app is a regular launcher application. It uses landscape for the wide usage dashboard, defaults to immersive full-screen display, and can remain visible when the device is locked while the dashboard is in front, but it never registers as the device Home app. A non-secure keyguard is dismissed on wake; a PIN, pattern, or password is never bypassed. Full-screen and screen-on behavior are explicit settings.

An optional `kiosk` flavor is available for a dedicated wall display. It adds the boot receiver, full-screen mode, and keep-awake behavior, but still never registers as the device Home app. It is never the default build.

## Pairing and privacy

Create a 10-minute pairing code while signed in to `https://u.80aj.com`, then enter it through the visible Settings button. The app exchanges it once for a read-only display token encrypted by Android Keystore. A direct display token is also accepted. The app calls only read-only endpoints over HTTPS: pairing, dashboard, analytics, history, display profile/avatar, and health. The last successful responses are cached on the device, so the screen keeps showing them (marked 离线) when the network is down.

## Screens

The dashboard is drawn natively (custom Canvas views, no WebView): 首页 / 用量 / 模型 / 统计 / 设置 plus a per-vendor detail page, designed for a 740x360 dp landscape screen. Code layout: `data/` (models, JSON parsing, formatting rules), `ui/` (theme, widgets, charts), `pages/` (one file per page plus the shell view).

Debug builds can render fixed demo data for screenshots; release builds do not contain the fixture:

```bash
adb shell am start -n com.usagehub.app.debug/com.usagehub.app.MainActivity --ez mock true --es tab home   # usage|models|stats|settings|settings-2|detail-claude
```

## Build

The checked-in Gradle wrapper requires Java 17.

```bash
# Normal app
./gradlew testStandardDebugUnitTest lintStandardDebug assembleStandardDebug --no-daemon

# Optional dedicated-display app
./gradlew testKioskDebugUnitTest lintKioskDebug assembleKioskDebug --no-daemon
```

The output APKs are under `app/build/outputs/apk/standard/debug/` and `app/build/outputs/apk/kiosk/debug/`.

## Versioning

`versionName` and `versionCode` are not edited by hand. The `v<major>.<minor>.<patch>` tag is the single
source of truth: `.github/workflows/android-release.yml` derives them (`v0.1.6` → `versionName 0.1.6`,
`versionCode 106`), passes them to Gradle as `-PusagehubVersionName` / `-PusagehubVersionCode`, and then fails
the build if `aapt dump badging` reports anything else. A bare local build falls back to the last released
version declared in `app/build.gradle.kts`.

```bash
# Reproduce a tagged release locally (unsigned without a keystore on purpose)
./gradlew assembleStandardRelease -PusagehubVersionName=0.1.6 -PusagehubVersionCode=106 --no-daemon
```

Main-branch CI also produces `app-standard-release.apk` with the repository's protected signing key. Install that artifact for stable in-place upgrades; Debug artifacts are not an upgrade channel.
