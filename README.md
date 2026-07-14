# Private DNS (Android)

One-tap setup for Android's built-in **Private DNS** (DNS-over-TLS, RFC 7858).
No root, no VPN slot used.

Built/tested 2026-07-14 on a Moto G Stylus 5G (Android 14), Kotlin + Jetpack Compose,
compileSdk 35 / minSdk 28 (Android 9+, where Private DNS exists).

## What it does

- Shows the current Private DNS state (`off` / `opportunistic` / strict `hostname`).
- Presets: Quad9 unfiltered (`dns10.quad9.net`, default), Quad9 filtered,
  AdGuard, Cloudflare, Mullvad, or any custom DoT hostname.
- **Apply / Automatic / Off** buttons write `private_dns_mode` /
  `private_dns_specifier` in `Settings.Global`.
- **Test** does a real end-to-end DoT check: TLS handshake to `<host>:853`
  (SNI + cert validation), then an A-record query for example.com over the
  tunnel, reporting TLS version, cert subject, resolved IPs, and latency.

## One-time permission grant

Writing secure settings needs a signature-level permission, grantable over adb
(the app shows this command with a copy button if it's missing):

```
adb shell pm grant com.privatedns android.permission.WRITE_SECURE_SETTINGS
```

## Build & install

```
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Requires JDK 17 and an Android SDK with platform 35 (`local.properties` points
at `~/Android/sdk`).

## Layout

- `app/src/main/java/com/privatedns/DnsController.kt` — Settings.Global read/write
- `app/src/main/java/com/privatedns/DotTester.kt` — raw DoT query/verify client
- `app/src/main/java/com/privatedns/MainActivity.kt` — Compose UI
