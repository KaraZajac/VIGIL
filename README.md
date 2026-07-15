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
detectable (see the research brief).

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
- an **RSSI proximity gate** — it must have been genuinely close (on-body/in-bag)
  at least once. This is the piece AirGuard omits, and it rejects "a Tile in a
  passing car."

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
Find My keys, a new one every 30 s) looks like 2,000 one-off devices and evades
all of them — it tracked a phone for 5 days with zero alerts.

VIGIL's headline goal is to detect the **attack, not the device**: a rotating
clone is one physical radio holding an unbroken, close-range, co-moving RF
*presence* even as its identity churns thousands of times faster than any
standards-compliant tracker is allowed to. The full algorithm — a CUSUM churn
trigger, an identity-agnostic presence-track confirmer, and a co-movement gate,
plus the "identity-path × churn-path squeeze" that leaves no safe rotation rate —
is designed in **[docs/detection-rotation-clone.md](docs/detection-rotation-clone.md)**.
It's now implemented as a first version in `detect/PresenceEngine.kt` (unit-tested
against synthetic clone/ambient traces) and wired into the scan service to raise a
distinct rotating-tracker alert; field-tuning against real captures is what remains.

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
rotation-clone presence engine (v1, unit-tested), foreground service, Compose UI.
**Not yet:** field-tuning the clone engine on real captures, GATT play-sound /
DULT get-identifier, and empirical validation. Before trusting the parser, capture real devices with nRF Connect —
the SmartTag2 offsets are inferred from gen-1, and the Tile/AirTag reversing is a
couple of years old.

## Credits & prior art

Stands on the shoulders of **[AirGuard](https://github.com/seemoo-lab/AirGuard)**
(TU Darmstadt / Seemoo-lab) and the SEEMOO Find My research, the IETF **DULT**
working group, Adam Catley's AirTag teardown, and Positive Security's *Find You*
clone research. VIGIL's aim is to go *beyond* AirGuard on the attacks it
structurally cannot catch — see the design doc.
