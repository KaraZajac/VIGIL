# VIGIL: On-Device Temporal Detection of Personal Location Trackers, Including Identity-Rotating Clones

**Kara Zajac** · Black Flag Intel
Draft v0.2 · 2026-10-10 · *working paper, accompanies the VIGIL reference implementation*

---

## Abstract

Consumer item-trackers — Apple AirTag, Tile, Samsung Galaxy SmartTag, and the
Google Find My Device network — have become instruments of stalking: in an online
survey of 5,253 people, recruited mostly through the AirGuard tracker-detection
app, 44.28% of respondents who had been stalked reported location tracking — by
Bluetooth tracker or other means — among the methods used against them, and cars
were the most common hiding place for a misused tracker [3]. Every deployed
countermeasure (Apple *Tracker Detect*, the iOS *Find My* and Android *Unknown
Tracker* alerts, and the open-source *AirGuard*) shares a structural assumption:
it **keys on a device identity** and alarms when the *same* identifier is seen
repeatedly while moving with the victim. This assumption fails in two directions.
It uses the decisive temporal signal — recurrence across the victim's *changing*
locations — only weakly; and it is defeated outright by identity-rotating clones —
in a 2022 test, a commodity ESP32 firmware that cycles ~2,000 Find My keys (a fresh
one every ~30 s) tracked a consenting test subject for five days without raising a
single alert [7].

We present **VIGIL**, an Android application that reframes the problem as
*temporal co-movement*: not "is a tracker near me" but "has *the same* tracker
been at enough of *my* distinct places, over enough time, and come at least
moderately near me." VIGIL contributes (1) a cross-ecosystem, fully on-device
detector that parses five BLE wire formats and gates alerts on a coarse RSSI
proximity test that AirGuard omits, together with a learned, on-device *baseline*
that auto-trusts trackers that recur where the user lives (in practice,
static-identity tags such as Tile); and (2) an identity-agnostic
**presence engine** that detects rotating-key clones by the one thing identity
churn cannot hide — an unbroken, coherent, co-moving RF *presence* — together
with a **rotation-rate "squeeze"** design in which an identity detector and a churn
detector leave no rotation rate that evades both. We are explicit that the shipped
thresholds do not yet close that squeeze: in offline replay, rotation periods from
about 85 seconds to about 45 minutes evade both detectors at the default
sensitivity (§5.4), and a clone that falls silent for more than 20 seconds every
few minutes is not detected (§7). VIGIL holds no network permission: the tracker
database, the learned baseline, and all inference stay on the device unless the
user exports an evidence report. We describe the per-ecosystem detection
methodology, the temporal model, the presence engine, the implementation, and an
evaluation plan with its synthetic results so far, and we are explicit about the
adaptive-adversary floor below which single-session on-device detection is
information-theoretically impossible.

---

## 1. Introduction

The same crowd-sourced location networks that help people find lost keys also let
a motivated abuser find a person. Apple's Find My network turns a US$29 coin-sized
beacon into a globe-spanning tracker that piggybacks on hundreds of millions of
other people's iPhones; Google's Find My Device network does the same across the
Android install base. Placed in a target's bag, coat, or car, such a beacon
reports the target's location without any infrastructure of the attacker's own.

Platform vendors responded with **unwanted-tracking alerts**, and the security
community produced **AirGuard** (TU Darmstadt), the best open-source detector.
These tools have worked, in their measured evaluations, *slowly and narrowly*: in a
February 2023 field test, an iPhone took 5 h 41 m to alert on an AirTag, shortly
after joining its home Wi-Fi, and never detected a Chipolo, while Samsung's own app
never found its own SmartTag [12]. That test and the 2022 clone test below both
predate the cross-platform alerts Apple and Google shipped in May 2024 [16]. All of
these detectors share the same core algorithm — **count
repeat sightings of one BLE identifier; alarm past a threshold** — and all of
them inherit its two structural weaknesses:

1. **Spatial, not temporal.** They reason about *presence*, so they need
   hand-tuned thresholds to avoid alarming on the many trackers legitimately
   around us. The discriminating fact — that a *stalking* tracker recurs across
   the victim's *changing* locations — is used only weakly.
2. **Identity-bound.** Because they track an identifier, any tracker that
   *rotates* its identifier faster than the accumulation window is invisible.
   Positive Security's *Find You* clone exploits exactly this: ~2,000 pre-loaded
   Find My public keys, one broadcast every ~30 s, so every sighting looks like a
   brand-new device. In a five-day carry test in February 2022, the tracked iPhone
   user got no alert, Apple's Tracker Detect found nothing, and AirGuard's manual
   scan showed only a stream of one-off devices; AirGuard's background mode was not
   tested [7].

**VIGIL** is built on the opposite premise. Where OVERWATCH (its sibling project)
asks *what is watching this place*, VIGIL asks *what has been with me* — it is a
temporal detector. Its contributions:

- **A cross-ecosystem, on-device temporal detector.** VIGIL parses Apple Find My,
  Google FMDN, Samsung SmartTag, Tile, and the unified DULT format, filters to the
  separated-from-owner state where signalled, and alerts on a device only when it
  clears a **co-movement test** across the user's distinct geographic cells, with an
  **RSSI proximity check** that AirGuard lacks, though a coarse one (§4.2). The
  "tracker in a passing car" false positive is rejected by counting each tag at
  most once per 15 minutes.
- **A learned, on-device baseline.** VIGIL learns the places a user dwells and
  auto-trusts trackers that recur there across days without co-moving — in
  practice static-identity tags such as a housemate's Tile, since daily-rotating
  identities never last three days — without a server and without the user
  tagging anything.
- **A presence engine for rotating clones.** VIGIL targets the attack class that
  defeats every deployed tool by abandoning identity tracking in favour of
  identity-agnostic *presence continuity*; in offline replay it confirms the
  published *Find You* configuration in about six minutes (§7). We give a squeeze
  argument (§5.4) for closing the evasion gap left by either detector alone, and
  measure the gap the shipped thresholds still leave.
- **A privacy architecture with no network.** VIGIL requests no `INTERNET`
  permission. There is no server, no account, and no telemetry.

We are equally explicit about what VIGIL *cannot* do (§5.4, §5.6, §10): v1 misses
clones that rotate every ~85 s to ~45 min at the default sensitivity or fall
silent for more than 20 s every few minutes, and even the full design has a
floor — a patient, low-duty-cycle adversary can push any on-device passive
detector from minutes-latency single-session to days-latency multi-session
detection.

---

## 2. Background and threat model

### 2.1 BLE trackers and the separated state

An item-tracker is a Bluetooth-Low-Energy beacon. When **near its owner** it is
connected or advertises a non-locatable payload; when **separated** from its owner
it broadcasts a payload that any nearby "finder" phone can encrypt-and-upload to
the vendor's network, letting the owner retrieve its location. Two facts make
passive third-party detection possible at all:

1. Only the **separated** state matters for stalking — a tracker with its owner is
   not following *you* — and separated trackers advertise loudly (every ~2 s).
2. To be findable, separated trackers **rotate their identifier slowly.** The
   Apple/Google **DULT** specification, and each vendor's implementation, rotate
   the advertised address/key roughly **once per 24 hours** in the separated state
   (versus ~15 minutes near-owner). A separated AirTag holds one identity for a
   full day, re-rolling at 04:00 local. This slow rotation is *deliberate* — it is
   what lets a victim's phone recognise the same tracker over time — and it is the
   invariant the clone attack violates and that VIGIL's presence engine exploits.

### 2.2 Threat model

**Adversary.** Plants one or more trackers on the victim (bag, vehicle, clothing).
May use (a) a genuine, unmodified tracker; (b) a genuine tracker with a physically
disabled speaker; or (c) a modified/cloned emitter (custom firmware, rotating
keys, silent). The adversary controls the tracker but not the victim's phone.

**Defender.** The victim's Android phone, running VIGIL, listening to BLE
broadcasts. It never connects to the tracker for detection (an optional,
user-initiated GATT "make it ring" is out of the detection path), and sends
nothing beyond the generic scan requests any BLE scan may send. It has no server.

**Goal.** Alert the victim, with useful latency and a tolerable false-positive
rate, that a tracker is travelling with them — *including* when the tracker rotates
its identity or is silent.

**Non-goals.** VIGIL does not track individuals, does not deanonymise tracker
owners beyond the vendor-provided reactive flows, and does not claim to defeat a
patient adaptive adversary in a single session (§5.6).

---

## 3. Per-ecosystem detection

VIGIL's scanner (`scan/BleTrackerScanner.kt`) runs a hardware-filtered BLE scan —
the controller wakes the app only on a signature match, which makes screen-off
scanning deliverable — and hands each match to a stateless parser
(`scan/TrackerParser.kt`) that recognises five wire formats. For each ecosystem we
describe (a) the on-air **signature** VIGIL matches, (b) how it reads the
**separated** state, and (c) the **identity** it keys on for temporal correlation.

### 3.1 Apple Find My / AirTag

**Signature.** BLE manufacturer-specific data under Apple company id `0x004C`,
message type `0x12` (offline finding), payload length `0x19` (25 bytes). After
Android strips the two company-id bytes, the payload is
`[type=0x12, length=0x19, status, key(22), keyTopBits, hint]`.

**Separated state.** The **status byte** carries a "maintained" bit (bit 2): set
when the owner was in range within the current rotation period, cleared when
separated. VIGIL treats a cleared maintained bit as separated and ignores short
frames that carry no key. (Setting the status byte to `0x00` is a known
first-party-alert-suppression trick; it leaves the bit cleared, so VIGIL still
counts such a tag. The reverse, a tag that always sets the bit, is taken on trust
and never escalates; see §10.)

**Identity.** The advertised P-224 public key **is** the identity, and — uniquely
— the BLE MAC is *derived from the key*, so both rotate together. VIGIL keys on
the advertised key bytes themselves, which are stable across the ~24 h separated
epoch. The 04:00 re-roll intentionally severs continuity; §5 addresses bridging it.

### 3.2 Google Find My Device network (FMDN / "Find Hub")

**Signature.** BLE service data (AD type `0x16`) under 16-bit UUID `0xFEAA` (the
Eddystone UUID; pairing uses a different service, `0xFE2C`). The first
service-data byte is the frame type.

**Separated state.** VIGIL reads it **in cleartext**: frame `0x40` = normal, frame
`0x41` = unwanted-tracking/separated. A subtlety with defensive consequences: the
`0x41` state is *server-triggered* — Google decides a tag has been separated
(~≥30 min) and a nearby Android device flips it. A freshly-separated stock tag is
therefore still rotating its EID (~every 1024 s) and MAC together and is **not
passively re-linkable** until flipped to `0x41`, at which point the MAC freezes for
24 h. VIGIL keys FMDN identity on the MAC, valid within the separated epoch.

### 3.3 Samsung Galaxy SmartTag / SmartTag2

**Signature.** BLE service data under UUID `0xFD5A` (the Samsung company id
`0x0075` is emitted by all Samsung gear and is not tag-unique; the service UUID is
the anchor). A connectable/setup tag also exposes a `smarttag` device name.

**Separated state.** The first service-data byte encodes a 3-bit state (bits 5–7):
`2` = lost (after 15 min), `3` = "overmature-lost" (after 24 h; triggers Samsung's
own anti-stalking), `4`–`6` = paired/connected. VIGIL treats states 2–3 as
separated. In overmature state the rotating privacy id becomes daily-static,
giving a ~24 h re-link window; in the first 24 h everything rotates every 15 min
and is not passively re-linkable.

### 3.4 Tile

**Signature.** BLE service data under `0xFEED` (activated), `0xFEEC`
(pre-activation), or the legacy `0xFE84`.

**Separated state.** *None.* A Tile emits no separated/lost-state beacon — it is
"always findable," advertising identically whether with its owner or lost.

**Identity.** Tile's decisive property: the **BLE MAC is static and never
rotates**, in any state. This makes Tile the easiest ecosystem to correlate (an
indefinite persistent key) but the one with no separated flag, so VIGIL treats
every Tile as a live candidate and relies entirely on persistence + co-movement.
Notably, Tile's "Anti-Theft Mode" — which hides a Tile from Tile's *own*
Scan-and-Secure and from platform alerts — changes nothing at the BLE layer, so a
third-party scanner such as VIGIL detects an anti-theft Tile identically to a
normal one, closing a documented stalking gap.

### 3.5 DULT (unified, emerging)

**Signature.** BLE service data under UUID `0xFCB2`.

**Separated state.** The near-owner bit is the least-significant bit of byte 14
(`1` = near owner, `0` = separated); a Network-ID byte identifies the vendor
network. VIGIL parses this for forward compatibility as trackers migrate to the
unified format.

### 3.6 Partner tags and summary

Chipolo, Pebblebee, eufy, Motorola, Jio, and Rolling Square do **not** carry a
brand-specific signature: each SKU advertises as the *network* it was provisioned
to (Apple `0x004C`/`0x12` or Google FMDN `0xFEAA`). VIGIL detects the network, not
the brand; legacy proprietary SKUs (classic Chipolo, original JioTag) join no
crowd network and are out of scope.

| Ecosystem | Signature | Separated signal | MAC rotation (separated) | Re-link window |
|---|---|---|---|---|
| Apple Find My | mfg `0x004C`, type `0x12` | status "maintained" bit cleared | ~24 h (re-roll 04:00), MAC = key | ~24 h |
| Google FMDN | svc data `0xFEAA`, frame `0x41` | cleartext frame `0x41` (server-gated) | 24 h once `0x41`; else ~1024 s | ~24 h once separated |
| Samsung SmartTag | svc data `0xFD5A` | state byte 2/3 (lost/overmature) | 24 h once overmature; else 15 min | ~24 h once overmature |
| Tile | svc data `0xFEED`/`0xFEEC`/`0xFE84` | none (always findable) | **none — static MAC** | indefinite |
| DULT (unified) | svc data `0xFCB2` | byte-14 LSB = 0 | ~24 h separated | ~24 h |

---

## 4. The temporal co-movement model

### 4.1 The reframing

A tracker being *near* a user is uninformative — modern environments are saturated
with them. The stalking signal is **persistence across the user's own changing
locations**: because the phone is (almost) always on the user, counting a
tracker's presence across the user's *distinct places* over time *is* the
co-movement measure, and it needs no second trajectory.

### 4.2 The test

Each parsed sighting is geotagged with a coarse fused-location fix and written to
an on-device store (`data/db/VigilDatabase.kt`) as `(trackerId, timestamp, rssi,
separated, lat, lon, geohash7)`, retained 14 days. Over the trailing 24 h a
tracker is scored on:

- **Effective sightings** — raw sightings debounced to at most one per 15 min, so a
  2 s-interval beacon cannot trivially inflate the count. Require ≥ 3.
- **Distinct places** — the number of distinct **geohash-7 cells** (~153 m) among
  its sightings. Require ≥ N, with N = 2/3/4 by sensitivity (high/medium/low).
- **Span** — first-to-last minutes. Require ≥ T, with T = 30/45/90 min.
- **RSSI proximity gate** — the raw peak RSSI must at least once reach −90/−85/−80
  dBm by sensitivity. **AirGuard has no such check.** It is a coarse gate, though:
  a tag can reach −85 dBm from well outside the user's bag, often tens of metres
  away in open air, so it only rejects tags that never came even moderately near.
  A tracker that merely shared a road with the user at a few red lights is stopped
  by the debounce instead: it counts at most once per 15 minutes, so a short
  stretch side by side never reaches three sightings.

A device escalates `OBSERVED → SUSPICIOUS → ALERTING` (`detect/CoMovementEvaluator.kt`).
ALERTING requires all four conditions; SUSPICIOUS requires three effective
sightings, a peak that passed the RSSI gate and at least one place, without yet
enough spatial/temporal spread. A tracker with no separated sighting in the window
never escalates (Tile, which has no flag, excepted). A sighting without a location
fix counts toward the sightings but not the places, so without location this path
cannot alert. These thresholds are adapted from AirGuard's field-tuned model (≥3
sightings, N distinct locations ≥150 m apart, T minutes) with the added RSSI gate.

### 4.3 The learned baseline and the allowlist

Two trust signals suppress the dominant benign class — the trackers that *belong*
in the user's life.

- **Allowlist.** The user marks a tracker "It's mine" and VIGIL stops alerting on
  that identity. A Tile keeps its identity; other tags change theirs at least daily
  (§3) and can come back as a new device.
- **Learned baseline** (`detect/BaselineManager.kt`). VIGIL notes the user's own
  location every 5 minutes; a geohash-6 cell (~1.2 × 0.6 km) earns at most one
  visit per 10 minutes and becomes an **anchor** (home, work, anywhere the user
  spends time) after 18 visits, about 3 h of dwell. A tracker seen at an anchor,
  while not co-moving, on ≥ 3 distinct (UTC) days is auto-marked *baseline-safe*
  and excluded from alerting; trust is revoked if it ever co-moves. Only an
  identity that lasts three days can qualify, so in practice this learns
  static-identity tags such as a housemate's Tile: AirTag, FMDN, SmartTag and DULT
  identities re-roll at least daily (§3). Other household tags stay quiet because
  they don't travel with the user, or report being near their owner. (Dwell
  sampling is a v1; the roadmap replaces it with stay-point clustering.)

---

## 5. The rotation-clone presence engine

This section addresses the attack class that defeats every deployed detector, and
is the paper's core modeling contribution. The full design is in
`docs/detection-rotation-clone.md`; we summarise the argument here.

### 5.1 The attack and why identity detectors fail

The *Find You* clone is one physical ESP32 radio that broadcasts a pool of ~2,000
Apple Find My public keys, cycling to a new one every ~30 s. To any identity-keyed
detector each key is a new device seen once, so the "same identifier ≥ 3 times"
counter never accumulates and the detector stays silent — measured at zero alerts
over five days in Positive Security's 2022 test [7].

### 5.2 The exploitable invariant

The defence begins with a standards fact: DULT, Apple, and Google **mandate ~24 h
rotation in the separated state**. A legitimate separated tracker therefore holds
one identity for a full day; a clone rotating every 30 s runs **~2,880× faster
than any specification-compliant device**. This is not a heuristic "that seems
suspicious" — it is a *categorical* violation of a standardised invariant, giving
a clean anomaly baseline.

### 5.3 Presence, not identity

VIGIL stops tracking *who* is present and starts tracking *whether one radio is
continuously present*. A rotating clone, to function as a tracker, must emit an
**unbroken stream of separated-state beacons at roughly stable close-range RSSI
that moves with the victim across locations where the ambient tracker population
fully turns over.** We formalise three identity-agnostic quantities:

- **Novel-identifier churn rate** `λ_novel` — the rate of never-before-seen
  separated identities (over a 5-min window against a 30-min novelty set). A real
  separated tracker ≈ 0.0007/min; the clone ≈ 2/min.
- **Presence continuity** — the duty cycle with which *some* separated beacon
  occupies the close band (RSSI ≥ ~−65 dBm), and the longest unbroken run.
- **Seamless-novel-handover chain** — the number of times the close-band identity
  *changes* to a **novel** id with **no RSSI discontinuity** (`|Δrssi| ≤ 8 dB`)
  and **no time gap** (`Δt ≤ 6 s`). One physical radio produces a long chain of
  such seamless handovers; a crowd of many radios at many distances produces
  gappy, RSSI-discontinuous handovers. This chain is the tell no amount of identity
  churn can fake while remaining a functioning tracker.

These are the design's quantities. v1 implements the handover chain (a 30-min
novelty horizon, with the RSSI step measured against an EWMA of the run) and gates
on the run's average handover rate rather than a windowed `λ_novel` (§5.4); it has
no duty-cycle measure, since a silence over 20 s simply ends the run.

The physics that make co-movement a hard discriminator: a static tag or an
independently-moving passer can remain in the close band (radius R ≈ 10 m) for at
most `2R / v_rel` — ≈ 1.5 s at driving speed. Only a co-moving emitter (relative
velocity ≈ 0) has unbounded dwell. So a close-band presence sustained across ≥ 3
distinct cells while the user is moving *cannot* be explained by any single
ambient source.

### 5.4 The pipeline and the squeeze

The designed pipeline has three streaming stages (all O(1)-ish per sighting,
on-device): a **CUSUM change-point** on `λ_novel` versus a rolling personal
baseline *triggers* an identity-agnostic **presence-track confirmer** (counting
seamless novel handovers and RSSI coherence), which alarms only behind a hard
**co-movement gate** (net displacement ≥ 500 m across ≥ 3 cells while moving).
The shipped v1 replaces the CUSUM trigger with a fixed gate: a presence run must
contain at least 12 seamless novel handovers at a sustained rate of at least 0.7
per minute.

The robustness argument is the **rotation-rate squeeze.** Run an identity detector
and a churn detector together; if the identity path catches every identity held
longer than the churn path's slowest rotation, no rotation period evades both:

```
rotation period:  30 s ─── 2 min ─── 5 min ──────────── 24 h
                  └ churn path catches ┘
                          └ overlap ┘
                                └────── identity path catches ──────┘
```

**That is the squeeze as designed. As shipped, it does not close.** Each path's
reach is set by its thresholds, and the offline replay harness (§7) measures both
by sweeping the rotation period of a carried clone:

| Sensitivity | Churn path catches | Identity path catches | Evades both |
|---|---|---|---|
| High | rotation ≤ ~85 s | identity held ≥ ~30 min | ~85 s to 30 min |
| Medium (default) | rotation ≤ ~85 s | identity held ≥ ~45 min | ~85 s to 45 min |
| Low | rotation ≤ ~85 s | identity held ≥ ~90 min | ~85 s to 90 min |

The churn ceiling is the v1 rate gate: 0.7 seamless novel handovers per minute is
one rotation every ~86 s. The identity floor is the co-movement test of §4.2:
three sightings, de-duplicated to one per 15 minutes, spanning the sensitivity's
minimum time, so a single identity must survive that whole span. The original
argument assumed an identity detector catches any key held longer than ~3–5 min.
Neither VIGIL nor AirGuard does: AirGuard's risk evaluator requires a device to
have been tracked for 30, 60 or 120 minutes, by sensitivity, before it alarms [2].
An adversary who rotates every few minutes — still hundreds of times faster than
the 24 h rotation the specifications mandate — evades both of VIGIL's detectors
today.

Closing the gap is open work. The design's answer is a presence-continuity path
that is rotation-*rate*-agnostic: slowing rotation lowers `λ_novel` but does
nothing to an unbroken, coherent, co-moving close-band track. v1 does not have that
path, because its rate gate deliberately limits the clone alarm to impossible
churn; any replacement must be scored against the false-positive budget of §7.

### 5.5 False positives

The engine's viability rests on dense-urban and transit false positives. Four
discriminators, strongest first: (1) the **seamless-handover chain** — a crowd is
many radios and cannot fake one continuous `(t, rssi)` track across 500 m; (2)
**co-movement across turnover** — bounded ambient dwell means a presence spanning
≥ 3 non-adjacent cells is not any single source (v1 checks ≥ 3 distinct cells and
≥ 500 m net displacement); (3) **RSSI coherence** — one radio yields a smooth RSSI
random-walk, a crowd a high-variance cloud; (4) **stationary suppression** — no
motion, no alarm, which removes the entire apartment/at-home class. The genuinely
hard case is **live rush-hour transit**, where boarding churn, vehicular
co-movement, and continuous occupancy align. The design's resolution is a
**mode-change gate**: a transit crowd cannot follow the victim *off the train and
down the street*, so escalation would wait until the coherent co-moving presence
survives a detected context transition, trading **latency** for transit-FP
suppression. v1 does not implement it; it relies on the churn gate and on RSSI
coherence, which caps an incoherent crowd below CONFIRMED, the only tier that
alerts. The synthetic rush-hour trace never opens the churn gate (§7); real
transit is untested.

### 5.6 The honest floor

Every evasion degrades the adversary's own tracker (slower/duty-cycled rotation
and reduced TX power mean fewer, coarser location fixes). Below some duty cycle — a
few randomly-timed, random-power beacons per 15 min — the emitter becomes
information-theoretically indistinguishable, *within a single session*, from sparse
ambient pass-bys, and detection must move to **cross-session recurrence** (the same
anomalous weakly-co-moving presence recurring day after day along the victim's
routes), which costs **days of latency** and requires opt-in persistent storage.
This is the floor of the full design: a patient, low-duty-cycle adversary forces
any on-device detector from minutes-latency single-session to days-latency
multi-session detection. VIGIL v1 stops working well before that floor: it has no
cross-session path, and a clone that goes silent for more than 20 s every few
minutes already evades it (§7, §10). VIGIL states this rather than claiming an
impossible guarantee.

---

## 6. Implementation

VIGIL is a native Android/Kotlin app (min SDK 26), reusing the scanning and
service architecture of OVERWATCH.

```
scan/TrackerSignatures.kt   BLE hex signatures for every ecosystem
scan/TrackerParser.kt       ScanResult -> TrackerObservation (five wire formats)
scan/BleTrackerScanner.kt   hardware-filtered scan (screen-off capable)
service/ScanService.kt      foreground service; geotags, persists, alerts, vibrates
data/db/VigilDatabase.kt    Room: trackers, 14-day sightings, baseline places, alerts
data/TrackerRepository.kt   ingest + baseline + evaluate on each sighting
detect/CoMovementEvaluator  the co-movement test + RSSI proximity gate
detect/BaselineManager.kt   anchor-place learning -> auto-trust static-ID tags
detect/PresenceEngine.kt    the rotation-clone engine of §5 (v1, unit-tested)
ring/TrackerRinger.kt       user-initiated GATT ring; RingReply.kt parses the reply
```

**Privacy properties.** The manifest declares **no `INTERNET` permission**; VIGIL
cannot open a socket. All state lives on the device — trackers, sightings, baseline
places and alerts in SQLite, the allowlist and settings in app preferences — and
VIGIL never transmits it; it leaves the phone only if the user exports the
evidence report or GPX. Cloud backup is disabled. `BLUETOOTH_SCAN` is declared
*without* `neverForLocation`, because detection is intentionally location-correlated
(and because that flag would filter out the very beacon-shaped advertisements
VIGIL parses). On the detection path VIGIL only listens: it never
advertises to or connects to a tracker (the phone's scan may send the generic scan
requests any BLE scan does).

---

## 7. Evaluation

VIGIL is a prototype. **No field evaluation has been run**, so no real-world
detection-rate claims are made here. What exists is the offline half of the
methodology below: a record/replay harness, run today on synthetic traces.

- **Red-team emitter.** A parameterised OpenHaystack/*Find You* clone. Carried, it
  is a real co-moving emitter — ground-truth positive without endangering anyone.
  The Flipper Zero implementation in `flipper/vigil_emitter/` sweeps rotation
  (30 s … static), duty cycle (100% … 10%) and fixed TX power (0 … −40 dBm);
  per-advert power jitter exists only in the simulator. Its keys are random and
  never registered, so it cannot report a location to anyone. It has not yet been
  used in a field run.
- **Ambient baselines (planned; none recorded yet).** Raw sighting logs across
  environment classes (rural drive, suburban, dense-urban walk, bus, rush-hour
  subway, highway, apartment block, crowd), each with paired emitter-on/off runs.
- **Record/replay harness.** Implemented in
  `app/src/test/kotlin/org/soulstone/vigil/eval/`. It replays a trace through both
  detection paths as the app wires them (the same 15 s throttle, 24 h window,
  cooldowns and CONFIRMED-only clone alerts), without the learned baseline or
  allowlist and treating every simulated sighting as separated and geotagged. It
  is deterministic, so a threshold change shows up immediately. Ambient logs are
  meant to be recorded once and replayed; attack-in-context scenarios are
  synthesised by merging clean and emitter-only traces.
- **Metrics.** Planned: true-positive rate at a fixed false-positive budget
  (headline: TPR at ≤ 1 FP per active-carry-day, per class), ROC/AUC, and
  ablations isolating each discriminator. Computed today, on synthetic traces:
  detection latency (attach → first alert), alerts per carry-day, and robustness
  sweeps over rotation period, TX-power jitter and duty cycle.

**Synthetic results so far.** The traces come from a log-distance path-loss model
with seeded noise, not from captures, so these numbers validate the detection logic
end to end and say nothing yet about real RF. The published *Find You*
configuration (a new key every 30 s, full duty, carried while walking) is confirmed
by the presence engine in about 6 minutes, while the identity path stays blind to
it. No alert fires in synthetic dense-urban, at-home, or rush-hour-transit ambient
traces. Two weaknesses are measured: the squeeze gap of §5.4, and duty cycling —
a clone emitting for 30 s and then silent for 30 s is never detected, because each
silence longer than 20 s ends the presence run.

---

## 8. Comparison to prior art

| Capability | Apple Tracker Detect | iOS / Android built-in | AirGuard | **VIGIL** |
|---|---|---|---|---|
| Cross-ecosystem | Find My only | AirTag + FMDN + DULT partners (not Tile) | yes | yes |
| Background / automatic | no (manual scan) | yes | yes | once started (not after reboot) |
| Fully on-device / no account | yes | yes | yes | **yes (no network permission)** |
| RSSI proximity gate on alerts | no | undisclosed | **no** | **yes** |
| Learned baseline (auto-trust) | no | no | manual ignore only | **yes (static-ID tags in practice)** |
| Detects rotating-key clones | no | no | no | **fast, continuous rotation only (≤ ~85 s; a silence > 20 s resets it); v1, simulated (§5.4, §7)** |
| Covers Tile anti-theft | no | no | yes (static MAC) | yes |
| Survivor-grade evidence export | no | no | limited | basic (text report + GPX) |

Cross-ecosystem, offline operation, and temporal co-movement are *not* novel —
AirGuard has them. VIGIL differentiates on the pieces AirGuard's architecture
forecloses: the coarse RSSI gate, the learned baseline, and above all the
identity-agnostic presence engine that targets the clone attack.

---

## 9. Privacy and ethics

VIGIL is a defensive, survivor-oriented tool. It maps and reasons about
*trackers*, never about people, and holds no network to which anything could be
exfiltrated. The learned baseline is a privacy *feature* — it exists so the app
does not need to phone home to know which tags are "normal." The dual-use surface
(BLE parsing that could in principle be repurposed for tracking) is mitigated by
the listen-only, no-network design. As with all anti-stalking tools, VIGIL should
be paired with survivor-safety guidance: a detected tracker can indicate an abuser
with physical access, and "make it ring / remove the battery" advice must be given
with situational-safety caveats.

---

## 10. Limitations

- **Single-session only (§5.6).** A patient low-duty-cycle adversary is only
  detectable across sessions, and VIGIL has no cross-session path yet: the identity
  path judges each identity over the trailing 24 h, and the presence engine starts
  fresh each session.
- **Near-owner and freshly-separated tags** are not passively re-linkable (Apple
  near-owner; default FMDN before the `0x41` flip; Samsung in the first 24 h).
- **Wire-format offsets** are drawn from published reverse-engineering and, for
  SmartTag2 and 2024+ Tile, inferred from older captures; they require empirical
  validation before production trust.
- **Coverage only while watching.** VIGIL scans continuously from a foreground
  service once the user starts it, but nothing restarts it after a reboot (§8).
- **Needs location.** Without a current location fix VIGIL can't count places or
  movement, so neither path can alert.
- **Squeeze gap (§5.4).** Rotation periods from ~85 s up to the identity path's
  minimum span (30, 45 or 90 min by sensitivity) evade both detectors in v1.
- **Duty cycling (§7).** Silences longer than 20 s reset the presence run, so a
  clone that pauses for more than 20 s every few minutes (30 s on / 30 s off, for
  example) is not detected; the reacquire rule of the design document is not yet
  implemented.
- **Self-reported separation.** VIGIL takes a tag's separated-state flag on trust.
  A tracker with no separated sighting stays OBSERVED, and only separated sightings
  reach the presence engine, so a modified tag that always claims its owner is
  nearby never escalates. Tile, which has no such flag, is the exception.
- **Not yet field-evaluated.** §7's results are synthetic.

---

## 11. Related work

Heinrich et al. reverse-engineered Apple Find My ("Who Can Find My Devices?", PETS
2021) and built AirGuard ("Protecting Android Users from Stalking Attacks by Apple
Find My Devices", WiSec 2022), later measuring real-world stalking prevalence and
detector efficacy ("Please Unstalk Me", PETS 2024). Mayberry et al. ("Who Tracks
the Trackers?", WPES 2021) built a custom Find My tracker that the network locates
without triggering Apple's item-safety alerts, and Shafqat et al. ("Track You",
PETS 2023) examined AirTag safety alerts in depth. Yu et al. (USENIX Security
2024) analysed Samsung's crowd-sourced Bluetooth location-tracking system, which
SmartTag uses, and Georgia Tech (2025) analysed Tile's static-MAC linkability.
Bräunlein/Positive Security demonstrated the rotating-key clone ("Find You").
Naturalistic evaluations ("Stop Following Me!", EuroUSEC 2024; tests run in 2023)
found deployed detectors rarely useful. The Apple/Google **DULT** IETF effort
standardises the separated-state signalling and a baseline detection algorithm.
VIGIL builds directly on this line and extends it to the identity-rotating
adversary that all of it currently misses. Change-point detection (Page 1954;
Basseville & Nikiforov 1993) and bounded-memory distinct counting (Datar et al.;
Flajolet et al.) underpin the presence-engine design; the identity-free
"sustained close presence" reasoning is anticipated by contact-tracing designs
(DP-3T; Google/Apple Exposure Notification).

---

## 12. Conclusion

The anti-stalking detectors people rely on use the temporal signal only weakly,
and are identity-bound where the adversary can rotate identities. VIGIL reframes
detection as temporal co-movement, adds the coarse proximity gate and learned
baseline that make that reframing practical on-device and private, and introduces
a presence engine that detects the fast-rotating clone by the one property
identity churn cannot hide. The rotation-rate squeeze shows how the combination
could leave no safe rotation rate; our own measurements show the shipped
thresholds do not yet achieve it, and the honest floor shows where even the full
design stops. We release VIGIL
as a working prototype and an open design under the BSD 3-Clause license, and
invite the field evaluation §7 lays out.

---

## References

1. A. Heinrich, M. Stute, T. Kornhuber, M. Hollick. "Who Can Find My Devices? Security and Privacy of Apple's Crowd-Sourced Bluetooth Location Tracking System." *PoPETs* 2021. https://petsymposium.org/popets/2021/popets-2021-0045.pdf
2. A. Heinrich, N. Bittner, M. Hollick. "AirGuard — Protecting Android Users from Stalking Attacks by Apple Find My Devices." *ACM WiSec* 2022. https://arxiv.org/pdf/2202.11813 · code: https://github.com/seemoo-lab/AirGuard
3. A. Heinrich, L. Würsching, M. Hollick. "Please Unstalk Me: Understanding Stalking with Bluetooth Trackers and Democratizing Anti-Stalking Protection." *PoPETs* 2024. https://petsymposium.org/popets/2024/popets-2024-0082.pdf
4. N. Shafqat et al. "Track You: A Deep Dive into Safety Alerts for Apple AirTags." *PoPETs* 2023. https://petsymposium.org/popets/2023/popets-2023-0102.pdf
5. T. Yu, J. Henderson, A. Tiu, T. Haines. "Security and Privacy Analysis of Samsung's Crowd-Sourced Bluetooth Location Tracking System." *USENIX Security* 2024, pp. 5449–5466. https://www.usenix.org/conference/usenixsecurity24/presentation/yu-tingfeng · preprint: https://arxiv.org/abs/2210.14702
6. Kumar, Raymaker, Specter. Tile protocol analysis. 2025. https://arxiv.org/html/2510.00350v1
7. F. Bräunlein / Positive Security. "Find You: Building a stealth AirTag clone." 21 Feb 2022. https://positive.security/blog/find-you · https://github.com/positive-security/find-you
8. A. Catley. "AirTag reverse engineering." https://adamcatley.com/AirTag.html
9. IETF DULT WG. "Detecting Unwanted Location Trackers" (accessory-protocol; threat-model). https://datatracker.ietf.org/wg/dult/
10. Google. "Find My Device network (Fast Pair FMDN) specification." https://developers.google.com/nearby/fast-pair/specifications/extensions/fmdn
11. SEEMOO-Lab. "OpenHaystack." https://github.com/seemoo-lab/openhaystack
12. K. I. Turk, A. Hutchings. "Stop Following Me! Evaluating the Effectiveness of Anti-Stalking Features of Personal Item Tracking Devices." *EuroUSEC* 2024 (tests run February 2023). https://arxiv.org/abs/2312.07157
13. E. S. Page. "Continuous Inspection Schemes." *Biometrika* 41, 1954. (CUSUM.)
14. M. Basseville, I. Nikiforov. *Detection of Abrupt Changes: Theory and Application.* 1993.
15. M. Datar, A. Gionis, P. Indyk, R. Motwani. "Maintaining Stream Statistics over Sliding Windows." *SODA* 2002.
16. Apple Newsroom. "Apple and Google deliver support for unwanted tracking alerts in iOS and Android." 13 May 2024. https://www.apple.com/newsroom/2024/05/apple-and-google-deliver-support-for-unwanted-tracking-alerts-in-ios-and-android/
17. T. Mayberry, E. Fenske, D. Brown, J. Martin, C. Fossaceca, E. C. Rye, S. Teplov, L. Foppe. "Who Tracks the Trackers? Circumventing Apple's Anti-Tracking Alerts in the Find My Network." *ACM WPES* 2021, pp. 181–186. https://doi.org/10.1145/3463676.3485616
18. P. Flajolet, É. Fusy, O. Gandouet, F. Meunier. "HyperLogLog: the analysis of a near-optimal cardinality estimation algorithm." *AofA* 2007 (DMTCS Proceedings). https://doi.org/10.46298/dmtcs.3545

*This working paper accompanies the VIGIL reference implementation. The rotation-clone
engine (§5) is specified in `docs/detection-rotation-clone.md` and implemented as a
first version in `detect/PresenceEngine.kt` (unit-tested against synthetic clone/ambient
traces) and wired into the scan service; field-tuning against real captures remains.*
