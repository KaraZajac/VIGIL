<div align="center">

<img src="docs/logo.png" alt="VIGIL" width="96" height="96" />

# VIGIL

**What's been following you?**

</div>

A native Android (Kotlin) app that watches for personal item-trackers — Apple
AirTags, Tile, Samsung Galaxy SmartTags, and Google Find My Device / DULT tags —
that are **travelling with you over time**. It is the temporal counterpart to
[OVERWATCH](https://github.com/KaraZajac/OVERWATCH):

> **OVERWATCH is spatial** — *what surveillance is watching this place, right now.*
> **VIGIL is temporal** — *what has been with me, across time and places.*

A tracker being *near* you means nothing — trackers are everywhere. The signal is
**persistence**: the same device seen at many of *your* distinct locations, over a
sustained window, while close enough to actually be on you. VIGIL is built around
that idea.

> ⚠️ **Prototype / work in progress.** The scanning, parsing, temporal store,
> co-movement engine, allowlist, and learned baseline are in place; the app builds
> in CI. Wire-format offsets and thresholds are from the research brief and need
> validation against real hardware (see [Status](#status)).

---

## Screenshots

<p align="center">
  <img src="docs/screenshots/main.png" width="230" alt="VIGIL watch list — whether anything has been following you" />
  &nbsp;
  <img src="docs/screenshots/finder-searching.png" width="230" alt="Hot/cold finder searching for a tracker" />
  &nbsp;
  <img src="docs/screenshots/finder-close.png" width="230" alt="Finder homing in on a device, with Make it ring" />
</p>

<p align="center"><sub>The watch list &nbsp;·&nbsp; the hot/cold finder searching &nbsp;·&nbsp; homing in on a tracker, with “Make it ring”</sub></p>

## Privacy first — fully offline

VIGIL requests **no `INTERNET` permission at all.** There is no server, no account,
no telemetry. Every tracker, every sighting, and the entire learned baseline live
in an on-device SQLite database and never leave the phone. **Detection is entirely
passive — it listens only.** The one exception is the user-initiated **"Make it
ring"** action, which connects to a tracker you already suspect and asks it to play
a sound (the DULT-standard way for a victim to locate a hidden tag); nothing is
transmitted unless you tap it.

---

## What it detects

Four native BLE wire formats today, plus the emerging unified DULT format. VIGIL
recognises each ecosystem and, where the ecosystem signals it, filters to the
**separated-from-owner** state — the only state in which a following tracker is
detectable (see the research brief). VIGIL takes that flag on trust, so a modified
tag that always claims its owner is nearby is never escalated. Tile has no such
flag, so every Tile is checked.

| Ecosystem | BLE signature | Separated-state signal | Passive re-link window |
|---|---|---|---|
| **Apple Find My / AirTag** | mfg data, company `0x004C`, type `0x12` | status byte "maintained" bit cleared | ~24 h (key static per day) |
| **Google Find My Device** | service data `0xFEAA`, frame `0x40`/`0x41` | frame `0x41` = separated (cleartext) | ~24 h once separated |
| **Samsung Galaxy SmartTag** | service data `0xFD5A` | state byte (lost / overmature-lost) | ~24 h once overmature |
| **Tile** | service data `0xFEED` / `0xFEEC` | none — static MAC, always findable | indefinite (static MAC) |
| **DULT (unified, emerging)** | service data `0xFCB2` | near-owner bit (byte 14 LSB) | ~24 h separated |

Chipolo, Pebblebee, eufy, Motorola, etc. inherit the signature of whichever
network (Apple or Google) their SKU joined — VIGIL detects the **network**.

## How it decides

Each parsed sighting is geotagged with a coarse fix and written to the temporal
store. A tracker is escalated `OBSERVED → SUSPICIOUS → ALERTING` only when it
clears the **co-movement test**:

- **≥ 3 sightings** (debounced to one per 15 min), across
- **≥ N distinct places** (geohash-7 cells; N = 2/3/4 by sensitivity), over
- **≥ T minutes** (30/45/90 by sensitivity), **and**
- an **RSSI proximity gate** — its peak signal must have reached −85 dBm at
  least once at the default setting (−90/−80 on High/Low). AirGuard has no such
  check, but it's a coarse one: a tag can clear it from tens of metres away. What
  stops "a Tile in the next car at a few red lights" is the debounce above. Each
  tag counts once per 15 minutes, so a short stretch side by side never adds up.

Two trust signals suppress false alarms:

- **Allowlist ("This is mine").** Tap a tracker to mark it approved — your own
  AirTag, your partner's Tile — and it never alerts again.
- **Learned offline baseline.** VIGIL learns the places you dwell (home, work) as
  *anchors*, and a tracker seen at an anchor across several distinct days is
  auto-marked **Known (home)**. So the household tags that are always around you
  fall silent on their own, entirely on-device.

## Finding a tracker

Tap any tracker for two ways to physically locate it:
- **Make it ring** — connects over GATT and plays the tracker's own sound. Works for
  AirTags (native `0xAF` sound), Google Find My Device, and DULT tags (Chipolo,
  Pebblebee, eufy, Motorola). Samsung SmartTag and Tile expose no non-owner ring, so
  VIGIL points you to the SmartThings / Tile app instead.
- **Hot/cold finder** — a passive proximity meter that turns live signal strength into
  a warmer/colder readout. It still works on **silent or modified tags that refuse to
  ring**, which is exactly when you need it most.

## The hard part — catching clones (problem #1)

Every shipping detector (AirGuard, iOS, Android's built-in) keys on **device
identity**. A key-rotating clone (e.g. Positive Security's *Find You*: ~2,000
Find My keys, a new one every 30 s) looks like 2,000 one-off devices to every one
of them. In Positive Security's 2022 test it tracked a phone for five days
with zero alerts, and AirGuard's manual scan showed only a stream of one-off
devices.

VIGIL's headline goal is to detect the **attack, not the device**: a rotating
clone is one physical radio holding an unbroken, close-range, co-moving RF
*presence* even as its identity churns thousands of times faster than any
standards-compliant tracker is allowed to. The full algorithm — a CUSUM churn
trigger, an identity-agnostic presence-track confirmer, and a co-movement gate,
plus the "identity-path × churn-path squeeze" meant to leave no safe rotation
rate — is designed in **[docs/detection-rotation-clone.md](docs/detection-rotation-clone.md)**.
A first version is implemented in `detect/PresenceEngine.kt` and wired into the
scan service to raise a distinct rotating-tracker alert.

**What it catches today**, measured in an offline replay harness on synthetic
traces (`app/src/test/kotlin/org/soulstone/vigil/eval/`): the published *Find You*
configuration, a new key every 30 s, is confirmed in about 6 minutes of walking,
with no alert in dense-urban, at-home or rush-hour-transit ambient traces.

**What it doesn't:** the squeeze is not closed by the shipped thresholds. A tracker
that rotates every ~85 seconds to ~45 minutes evades both detectors at the default
sensitivity (to 30 min on High, 90 min on Low), and a clone that goes silent for
30 s at a time is never detected. Both are open work, and none of these results has
been checked against real RF yet — the Flipper Zero emitter in
[`flipper/vigil_emitter/`](flipper/vigil_emitter/) exists to do that.

## Architecture

VIGIL reuses OVERWATCH's proven scanning stack and diverges where the temporal
mission demands it (a persistent database instead of an in-memory store).

```
scan/TrackerSignatures.kt   BLE hex signatures for every ecosystem
scan/TrackerParser.kt       ScanResult -> TrackerObservation (four wire formats)
scan/BleTrackerScanner.kt   filtered BLE scan (screen-off capable) -> observations
service/ScanService.kt      foreground service; geotags + persists + alerts
data/db/VigilDatabase.kt    Room: trackers, sightings (14-day), baseline places
data/TrackerRepository.kt   ingest + baseline + evaluate on every sighting
detect/CoMovementEvaluator  the temporal co-movement test + RSSI proximity gate
detect/BaselineManager.kt   learns anchor places -> auto-trusts household tags
data/location/LocationProvider.kt   fused location (ported from OVERWATCH)
ui/ + MainActivity.kt       Compose UI (Catppuccin Mocha)
```

## Paper

A full technical write-up — the per-ecosystem detection methodology (how VIGIL
tracks each tracker type) and the harder modeling engine for what AirGuard and the
built-in detectors miss — is in **[paper/vigil.md](paper/vigil.md)**.

## Build

Standard Android/Gradle. A committed debug keystore signs CI and local builds
identically.

```bash
./gradlew :app:assembleDebug
# APK -> app/build/outputs/apk/debug/app-debug.apk
```

CI (`.github/workflows/build.yml`) builds a debug APK on every push and attaches
an APK to a GitHub Release on `v*` tags.

## Status

Prototype. In place: BLE scan + filters, per-ecosystem parsing, Room temporal
store, co-movement evaluator with RSSI gate, allowlist, learned baseline, the
rotation-clone presence engine (v1, unit-tested), foreground service, Compose UI,
"Make it ring" over GATT, and evidence export.
**Not yet:** closing the squeeze gap and duty-cycle evasion described above,
field-tuning the clone engine on real captures, DULT get-identifier, and empirical
validation. Before trusting the parser, capture real devices with nRF Connect —
the SmartTag2 offsets are inferred from gen-1, and the Tile/AirTag reversing is a
couple of years old.

## Credits & prior art

Stands on the shoulders of **[AirGuard](https://github.com/seemoo-lab/AirGuard)**
(TU Darmstadt / Seemoo-lab) and the SEEMOO Find My research, the IETF **DULT**
working group, Adam Catley's AirTag teardown, and Positive Security's *Find You*
clone research. VIGIL's aim is to go *beyond* AirGuard on the attacks it
structurally cannot catch — see the design doc.

## License

VIGIL is released under the [BSD 3-Clause License](LICENSE). The web fonts in
`docs/fonts/` are separately licensed under the SIL Open Font License 1.1; see
[`docs/fonts/OFL.txt`](docs/fonts/OFL.txt).
