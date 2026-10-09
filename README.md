# OxiPro Bridge

Reads live readings from the OxiPro BP2 over Bluetooth LE (standard GATT
Blood Pressure Service `0x1810`) and writes them straight into Android
Health Connect.

[![Android build](https://github.com/Darenn71/OxiPro-BP2-HealthConnect/actions/workflows/android.yml/badge.svg)](https://github.com/Darenn71/OxiPro-BP2-HealthConnect/actions/workflows/android.yml)

## Download

**[⬇ Download the latest APK](https://github.com/Darenn71/OxiPro-BP2-HealthConnect/releases/latest/download/oxipro-bridge.apk)**

Built automatically on GitHub from the latest code. Open it on your Android
phone and allow installing from your browser/files app when asked. All
builds are listed on the [Releases page](https://github.com/Darenn71/OxiPro-BP2-HealthConnect/releases).

## What's here

- `app/src/main/java/com/oxipro/bridge/ble/BleManager.kt` — scans, connects,
  subscribes to indications on the BP Measurement characteristic (`0x2A35`)
- `app/src/main/java/com/oxipro/bridge/ble/BloodPressureParser.kt` — decodes
  the IEEE-11073 SFLOAT payload into systolic/diastolic/pulse/timestamp
- `app/src/main/java/com/oxipro/bridge/health/HealthConnectManager.kt` —
  writes `BloodPressureRecord` (+ `HeartRateRecord` if pulse present) via the
  Jetpack Health Connect SDK
- `app/src/main/java/com/oxipro/bridge/MainActivity.kt` — bare-bones screen
  wiring permissions → scan → connect → auto-write on every reading

## One-time setup (VS Code + PowerShell, no Android Studio required)

This project targets the **current stable toolchain** (as of August 2026):
Android Gradle Plugin 9.3.0, Gradle 9.5.0, built-in Kotlin support. Gradle
9.1+ fully supports running under JDK 25, so if you already have JDK 25
installed there's no need to install JDK 17 separately.

1. **Confirm your JDK**:
   ```powershell
   java -version
   ```
   Anything 17–25 works. If you don't have a JDK at all, grab the Temurin
   MSI from https://adoptium.net/temurin/releases/ (winget isn't required —
   see note below if `winget` isn't recognized on your machine).

2. **Install the Android command-line SDK tools** (no full Android Studio
   needed). Download "Command line tools only" from
   https://developer.android.com/studio#command-tools, unzip, then:
   ```powershell
   $env:ANDROID_HOME = "$HOME\AppData\Local\Android\sdk"
   mkdir "$env:ANDROID_HOME\cmdline-tools\latest" -Force
   # move the unzipped cmdline-tools contents into that folder
   $env:Path += ";$env:ANDROID_HOME\cmdline-tools\latest\bin;$env:ANDROID_HOME\platform-tools"

   sdkmanager --licenses
   sdkmanager "platform-tools" "platforms;android-36" "build-tools;36.0.0"
   ```

3. **Generate the Gradle wrapper.** This project already ships
   `gradle/wrapper/gradle-wrapper.properties` pinned to Gradle 9.5.0, so once
   you have *any* local Gradle install (even an old one) you can run:
   ```powershell
   cd oxipro-bridge
   gradle wrapper
   ```
   This downloads the wrapper jar matching the 9.5.0 URL already in that
   properties file. If you have no Gradle at all yet, download the binary
   zip from https://gradle.org/releases/, unzip it, add its `bin` folder to
   PATH for this one command, then remove it from PATH afterward — you
   won't need a standalone Gradle again once `gradlew.bat` exists.

   From then on use `.\gradlew.bat` for everything — it downloads and pins
   the exact 9.5.0 version itself, so teammates don't need Gradle installed
   globally at all.

4. **VS Code extensions**: "Kotlin Language" (fwcd.kotlin) is enough — use
   `adb`/`gradlew` from the integrated PowerShell terminal rather than
   Android Studio's UI.

**Note on `winget`:** if `winget install ...` commands fail with "term not
recognized," `winget` (App Installer) simply isn't present on your machine.
Install it from the Microsoft Store, or skip it entirely and download
installers directly from the links above.

## Build & install

```powershell
cd oxipro-bridge
.\gradlew.bat assembleDebug
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

## Before it'll work against the real device

1. **Confirm the GATT profile.** `BleManager` currently assumes the OxiPro
   BP2 exposes the standard Blood Pressure Service (`0x1810`). This is a
   reasonable bet since the device integrates with MedM's Health Diary app
   (which aggregates dozens of BP monitor brands via the standard profile),
   but it isn't confirmed. Run the app, tap "Connect & Sync", and check the
   status text:
   - If you see "Subscribed to Blood Pressure Measurement indications" —
     you're set, standard profile confirmed.
   - If you see "service not found" — the device uses a vendor-specific
     profile instead. Capture an HCI snoop log while pairing with the real
     MedM app (Developer Options → Enable Bluetooth HCI snoop log → pair →
     pull `/sdcard/btsnoop_hci.log` via `adb pull` → open in Wireshark) to
     find the actual service/characteristic UUIDs and byte layout, then
     update the UUID constants and `BloodPressureParser`.

2. **Narrow the scan filter.** `BleManager.startScan` currently matches any
   advertised name containing "BP" or "OxiPro" — tighten this to the exact
   advertised name once you've confirmed it (check via `nRF Connect` app or
   the scan log).

3. **Health Connect app** must be installed on the test device (Play Store,
   or built into the OS on Android 14+).

## Version summary

| Component | Version |
|---|---|
| Android Gradle Plugin | 9.3.0 |
| Gradle | 9.5.0 |
| Kotlin (via AGP built-in support) | bundled, min 2.2.10 |
| compileSdk / targetSdk | 36 |
| minSdk | 26 |
| Health Connect client | 1.1.0 |

## Result screen

After each reading, the app shows a colour-coded card:

- **Systolic / diastolic** — big numbers, coloured green (normal), amber
  (low), or red (high) against the 2017/2025 ACC/AHA thresholds (Normal
  <120/<80, low BP <90/<60, everything at or above 120/80 trending toward
  amber/red as it climbs through Elevated → Stage 1 → Stage 2 → crisis).
- **Pulse** — green 60–100 bpm (AHA's normal resting range), amber below 60,
  red above 100.
- **Pulse pressure** (systolic − diastolic) — this one is intentionally
  *not* a straight gradient. Clinically, both a narrow gap (<25 mmHg) and a
  wide gap (≥100 mmHg) are associated with cardiovascular risk; the healthy
  range (~30–50 mmHg) sits in the middle. So it's coloured red at both
  extremes and green in the middle, with amber as a buffer zone on either
  side — see `BpEvaluator.kt` for the exact cutoffs and sources.
- A short feedback paragraph summarising the overall AHA category and
  anything notable about pulse pressure or pulse rate.

This is general reference information, not medical advice — the thresholds
are standard published ranges, not personalized to any individual, and
persistent abnormal readings are always worth a real conversation with a
doctor.

## Known simplifications to revisit

- No retry/backoff on BLE connection drops.
- Programmatic UI in `MainActivity` — swap for a real layout/ViewModel.
- No local persistence — a failed Health Connect write currently just shows
  an error and drops the reading; add a retry queue if you want durability.
