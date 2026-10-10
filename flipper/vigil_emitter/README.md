# VIGIL Emitter (Flipper Zero)

A red-team beacon for **validating VIGIL** — the ground-truth-positive emitter that
[`paper/vigil.md`](../../paper/vigil.md) §7 and
[`docs/detection-rotation-clone.md`](../../docs/detection-rotation-clone.md) §5 call for,
built on hardware that's already in the bag instead of an ESP32.

It advertises **Apple Find My "offline finding" frames** — the exact wire format
`scan/TrackerParser.kt` parses — with a **sweepable key-rotation period** and
**duty cycle**, so you can carry it and watch what VIGIL does.

## It is not a tracker

A real Find My beacon advertises a P-224 public key **registered to an Apple ID**;
that registration is the whole mechanism by which an owner pulls location reports
back out of the Find My network. The keys here are **raw CSPRNG output generated on
the Flipper and never registered anywhere**, so the beacon reproduces the
advertisement *shape* VIGIL detects while being structurally unable to report its
position to anyone. It is a detection target, not a functioning tracker.

**Use it on yourself.** Carry it; don't attach it to another person or their
property. One caution worth knowing: in **static** rotation mode it holds a single
identity for the whole run, duty-cycle silences included, which is precisely what
other people's unwanted-tracking detectors (iOS Find My, Android's built-in,
AirGuard) are built to notice — so a long static run in a crowd can raise alerts on
strangers' phones. Prefer short static runs, or run them somewhere you aren't
trailing other people around for an hour.

## Build & install

Needs the Flipper SDK. Either:

```bash
# with ufbt (standalone app SDK)
cd flipper/vigil_emitter && ufbt && ufbt launch

# or from a firmware tree
ln -s "$PWD/flipper/vigil_emitter" <firmware-tree>/applications_user/vigil_emitter
cd <firmware-tree> && ./fbt fap_vigil_emitter
```

Enable Bluetooth on the Flipper first (Settings → Bluetooth). The app shows
`BT OFF?` if the radio refuses to start.

## Controls

| Key | Action |
|---|---|
| ↑ / ↓ | select field |
| ← / → | change value |
| OK | start / stop emitting |
| Back | exit (always stops the beacon) |

**Rotation** 30 s · 60 s · 90 s · 2 min · 5 min · 15 min · 45 min · static
**Duty** 100% · 50% · 20% · 10% (a fixed 30 s emitting window plus 30 / 120 / 270 s of silence)
**TX power** 0 · −4 · −8.9 · −14 · −21 · −40 dBm, fixed for the run

Rotation and duty run on one clock from the start of the run, the way
`SimTraces.CloneConfig` in the offline harness
(`app/src/test/kotlin/org/soulstone/vigil/eval/`) models them: a key lasts one
rotation period of wall time whether or not the radio is in a silent window, and
resuming after silence keeps the same key unless a rotation boundary passed. So a
field run is comparable to a simulated one, with two differences: the rotation list
is a subset of the harness sweep, which also runs 30, 60, 90 and 120 min and 24 h,
and the harness's per-advert TX-power jitter has no equivalent here. Changing any
setting while emitting starts a new run: fresh key, clock and duty phase.

## Field protocol

VIGIL's co-movement gates need real displacement and signal. The clone path needs
**≥ 500 m net across ≥ 3 geohash-7 cells** (~150 m) and only counts adverts at
**≥ −65 dBm**. The identity path needs three sightings, counted at most once per
15 min, across ≥ 3 cells over ≥ 45 min, with at least one advert reaching −85 dBm —
that's Medium, the default; High needs 2 cells, 30 min and −90 dBm, Low 4 cells,
90 min and −80 dBm. A test that doesn't leave the block will not alert, and that is
correct behaviour, not a failure; neither is silence at the lowest TX-power
settings, which can keep a pocketed Flipper below the clone path's −65 dBm band by
design. So: start VIGIL watching, start the emitter, put the Flipper in a bag or
pocket, and **walk**.

The offline harness predicts the following, and each is worth confirming on real RF —
the simulation models path loss and mobility, not antennas, bodies, or buildings:

| Emitter setting | Harness prediction | What you should see |
|---|---|---|
| 30 s, 100% duty | clone path CONFIRMED at ~6 min | "Possible hidden tracker" notification |
| static, 100% duty | identity path ALERTING at ~45 min | "Tracker following you" + a card in the list |
| 5 min, 100% duty | **neither path** | silence — the measured squeeze gap |
| 30 s, 50% duty | **neither path** | silence — presence runs break on the 30 s gaps |

The last two rows are the point. They are the two soft spots the harness found, and
confirming them on hardware is what turns a simulation result into a finding.

## Reading the result

VIGIL's own detail sheet is the readout: open the tracker card for "Co-movement
sightings" and "Distinct places", and use the alert history (clock icon) plus
**Export evidence** to get a timestamped report and GPX of the run. `adb logcat -s
ScanService` shows `ALERT:` and `CLONE ALERT:` lines with their timing if you want
latency to the second.
