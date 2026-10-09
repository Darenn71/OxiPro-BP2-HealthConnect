# OxiPro BP2 → Android Health Connect Bridge

## Technical write-up

This document describes the OxiPro Bridge Android app: what it does, how the
OxiPro BP2's Bluetooth Low Energy protocol was reverse-engineered, and what's
still unresolved.

---

## 1. Overview

The OxiPro BP2 is a Bluetooth LE blood pressure monitor sold with the MedM
"Health Diary" app. MedM supports email/CSV/WhatsApp export but has no direct
integration with Android Health Connect. This project is a small standalone
Android app that connects to the BP2 directly over BLE, decodes its readings,
and writes them into Health Connect — sitting alongside MedM rather than
replacing it.

**Stack:** Kotlin, Android Gradle Plugin 9.3.0 / Gradle 9.5.0 (built-in
Kotlin support, no separate Kotlin plugin), Jetpack Health Connect client
1.1.0, minSdk 26, compileSdk/targetSdk 36.

---

## 2. Bluetooth LE protocol

### 2.1 It is not the standard Bluetooth SIG profile

The obvious assumption going in was that the BP2 would implement the
standard Bluetooth SIG **Blood Pressure Service** (`0x1810`) with its
**Blood Pressure Measurement** characteristic (`0x2a35`), since that's a
well-documented profile and MedM aggregates dozens of unrelated monitor
brands — a strong signal (though not proof) that most of them speak a common
protocol. On connecting, service discovery confirmed this assumption was
wrong: `0x1810` is not present on the device.

### 2.2 Reverse-engineering method

With the standard profile ruled out, the protocol was reverse-engineered
from an Android **Bluetooth HCI snoop log** captured while using the real
MedM app to take and sync a reading:

1. Enabled Developer Options → "Enable Bluetooth HCI snoop log" and "USB
   debugging".
2. Restarted Bluetooth to start a clean capture.
3. Took a reading on the physical device using the official MedM app.
4. Pulled the capture via `adb bugreport` (direct `adb pull` of the log path
   is blocked on modern Android without root; the log is bundled inside the
   generated bug report zip at `FS/data/misc/bluetooth/logs/btsnoop_hci.log`).
5. Opened the extracted `btsnoop_hci.log` in Wireshark/tshark and filtered
   on the `btatt` (Bluetooth Attribute Protocol / GATT) display filter to
   isolate the relevant traffic.

### 2.3 Service and characteristics

GATT discovery in the capture showed a vendor-specific service, not present
in the Bluetooth SIG assigned-numbers registry:

| Role | UUID (short form) | Full UUID |
|---|---|---|
| Service | `0xffe0` | `0000ffe0-0000-1000-8000-00805f9b34fb` |
| Notify (device → phone) | `0xffe1` | `0000ffe1-0000-1000-8000-00805f9b34fb` |
| Write (phone → device) | `0xffe2` | `0000ffe2-0000-1000-8000-00805f9b34fb` |

`0xffe1`/`0xffe2` is a common "vendor UART-like" characteristic pairing used
by a number of cheap BLE peripherals (similar in spirit to Nordic's UART
service, but with entirely custom framing rather than raw byte streaming).

### 2.4 Packet framing

Every packet observed in the capture follows one of two 2-byte magic-number
prefixes depending on direction:

```
d0 c2 [cmd:1] [payload...] [trailer]      -- device -> phone, on 0xffe1
be b0 [len:1] [payload...] [trailer]      -- phone -> device, on 0xffe2
```

The byte immediately after the magic number serves double duty: on
device→phone packets it's a command/type byte; on phone→device packets it
appears to double as a payload-length byte (payload length matches this
value across every sample checked).

### 2.5 Handshake

After subscribing to notifications (writing `0x0001` to the Client
Characteristic Configuration Descriptor on `0xffe1`), the app writes one
fixed 5-byte "hello" command to `0xffe2`:

```
be b0 01 b2 72
```

This byte sequence was identical, byte-for-byte, in every capture session
regardless of date/time or device state — the only handshake command that
could be safely hardcoded and replayed verbatim. The official MedM app also
sends several other handshake commands (status query, record-index queries,
etc.) immediately after this one, but none of those were required to
observe the device push live and final-result notifications once
subscribed, so the app only replays the one constant command.

### 2.6 Live cuff-pressure stream

While the cuff deflates, the device pushes a notification roughly every
0.5–1 second:

```
d0 c2 04 cb [pressure_hi] [pressure_lo] [trailer:2]
```

`cmd = 0x04`, `subtype = 0xcb`, and the pressure value is a plain 2-byte
big-endian integer in mmHg. In the captured session this streamed from
155 mmHg down to 67 mmHg over roughly 90 seconds — a textbook oscillometric
deflation curve. This is decoded and surfaced in the app as a live
"Measuring... N mmHg" status message; it isn't required for the final
result and is purely a nicety.

### 2.7 Final result packet

Once the measurement completes, exactly one notification arrives with:

```
d0 c2 0c cc [systolic] [diastolic] [pulse] 00 [year_hi] [year_lo] [month] [day] [hour] [min] [sec] [trailer]
```

`cmd = 0x0c`, `subtype = 0xcc`. Confirmed against a real reading where the
device's own screen displayed **120/69, pulse 76**:

| Byte (hex) | Decimal | Field |
|---|---|---|
| `78` | 120 | Systolic (mmHg) |
| `45` | 69 | Diastolic (mmHg) |
| `4c` | 76 | Pulse (bpm) |
| `07 ea` | 2026 | Year (embedded device timestamp) |
| `08` | 8 | Month |
| `1f` | 31 | Day |
| `0f` | 15 | Hour |
| `0b` | 11 | Minute |
| `1e` | 30 | Second |

The embedded year/month/day/hour/minute/second matched the exact real-world
capture time, which both confirmed the byte layout and confirmed the
device's internal clock was already correctly set at the time of capture
(see §3 on why that matters).

### 2.8 Checksum/trailer bytes

Every packet ends in one or more trailer bytes that behave like a checksum
or CRC. This was **not** successfully reverse-engineered:

- Tested simple XOR and additive (sum mod 256) checksums over the payload,
  with and without the magic-number header, against multiple known-good
  samples — none matched.
- More conclusively: two *different* capture sessions contained the exact
  same payload bytes (`d7 ff 01`) for one particular command, but with
  different trailer bytes (`6f` in one session, `51` in the other). Since
  the input was identical but the output differed, the trailer cannot be a
  pure function of the visible payload alone — it depends on some hidden
  state (a session counter, a rolling value seeded during connection setup,
  or similar) that two capture sessions aren't enough data to solve.

**Practical consequence:** the app never computes or sends a trailer for
anything beyond the one hardcoded, invariant handshake command (§2.5). It
does not attempt to construct or send any other command, including the
time-sync command described next.

---

## 3. Time/date sync — known incomplete

The capture also revealed a distinct command for setting the device's clock:

```
be b0 08 b1 [year_hi] [year_lo] [month] [day] [hour] [min] [sec] [checksum:1]
```

`len = 0x08`, `subtype = 0xb1`, followed by a 6-field date/time payload in
the same layout as the final-result packet's embedded timestamp, plus a
1-byte checksum.

**This is not implemented, and intentionally so.** Because the checksum
algorithm is unresolved (§2.8), and because this particular command's
payload is date-dependent (it changes on every use, unlike the invariant
handshake), there's no way to compute a valid checksum for it without first
solving the checksum algorithm. Sending a plausible-looking but
incorrectly-checksummed packet risks the device silently rejecting it at
best; without the vendor's documentation there's no way to be fully certain
of the failure mode on a device the app doesn't own responsibility for.

**Impact of leaving this out:** minimal. The app never reads or trusts the
device's embedded timestamp for anything — every reading written to Health
Connect is stamped with the **phone's own system clock** at the moment the
notification arrives, not the device's internal clock. The only cost of not
implementing time-sync is cosmetic: if the OxiPro BP2's internal clock is
ever wrong (e.g. after a battery change), its own on-device screen may show
an incorrect date/time when browsing its stored reading history. It has no
effect on data written to Health Connect via this app.

**Path to resolving it, if wanted:** would need several more paired capture
sessions specifically designed to isolate the checksum function — e.g.
capturing the same date/time payload sent twice in separate sessions (by
temporarily setting the phone's clock to the same value twice and using
MedM's manual time-sync action) to get multiple same-input/different-output
samples, or varying a single field (say, just the seconds byte) across
several captures to observe how the checksum responds to a minimal, known
change. Two samples, as currently available, aren't enough to determine the
algorithm with confidence.

---

## 4. Health Connect integration

- Uses the Jetpack **Health Connect client** (`androidx.health.connect:connect-client:1.1.0`).
- Writes both a `BloodPressureRecord` (systolic/diastolic, in mmHg via the
  `Pressure` unit type) and a `HeartRateRecord` (single sample, pulse in
  bpm) per reading, both timestamped with the phone's clock (§3).
- Requires `android.permission.health.WRITE_BLOOD_PRESSURE` and
  `WRITE_HEART_RATE`, requested at runtime via
  `PermissionController.createRequestPermissionResultContract()`.
- The manifest declares a `<queries>` entry for
  `com.google.android.apps.healthdata` (required since Android 11's package
  visibility restrictions — without it, the app can't even see that Health
  Connect is installed) and the `VIEW_PERMISSION_USAGE` /
  `HEALTH_PERMISSIONS` intent filter Android 14+ requires before it'll allow
  a permission request at all.
- Writing is **manual**, not automatic: each parsed reading is held and
  displayed on screen, and only written to Health Connect when the user taps
  "Save to Health Connect".

---

## 5. Result screen and reference ranges

Each reading is shown with systolic/diastolic/pulse/pulse-pressure values,
colour-coded (green/amber/red) against published reference ranges, plus a
plain-language feedback summary. Full logic and inline citations are in
`BpEvaluator.kt`; source list:

- **Blood pressure categories** (Normal/Elevated/Stage 1/Stage 2/Crisis,
  and the common <90/<60 threshold for low blood pressure): 2017 ACC/AHA
  hypertension guideline, reaffirmed in the 2025 update.
- **Resting heart rate** (normal 60–100 bpm): American Heart Association.
- **Pulse pressure** (normal range ~30–50 mmHg; narrow <25 mmHg and wide
  ≥100 mmHg both flagged as higher-risk, since pulse pressure risk is
  U-shaped rather than a straight low-to-high gradient): Cleveland Clinic.

This is general reference information for the app's own display, not a
diagnosis, and the app footer says as much.

---

## 6. Known open items

1. **Checksum/trailer algorithm unresolved** (§2.8) — blocks sending any
   command beyond the one hardcoded handshake, including time-sync (§3).
2. **BLE scan/connect race condition, fixed** — `onScanResult` fires once
   per advertisement packet (every ~100ms–1s), and `stopScan()` is
   asynchronous; without a guard, multiple in-flight results could each
   trigger `connect()`, stacking overlapping `connectGatt()` calls and
   causing visible connect/disconnect churn before settling. Fixed by
   latching scan handling to fire only once per scan session.
3. **Device-side "read stored records" flow not implemented** — the capture
   showed a separate command sequence (`d8`/`d9` subtypes) the official app
   uses to fetch previously stored readings from the device's own memory.
   Not needed for the live-reading flow this app implements, but would be a
   separate reverse-engineering effort if historical-record import is ever
   wanted.
