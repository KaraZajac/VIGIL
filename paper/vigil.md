# VIGIL: On-Device Temporal Detection of Personal Location Trackers, Including Identity-Rotating Clones

**Kara Zajac** · Black Flag Intel
Draft v0.1 · 2026-07-15 · *working paper, accompanies the VIGIL reference implementation*

---

## Abstract

Consumer item-trackers — Apple AirTag, Tile, Samsung Galaxy SmartTag, and the
Google Find My Device network — have become instruments of stalking: in a survey
of intimate-partner-violence survivors, 44% of those stalked reported physical
location tracking, with vehicles the most common hiding place. Every deployed
countermeasure (Apple *Tracker Detect*, the iOS *Find My* and Android *Unknown
Tracker* alerts, and the open-source *AirGuard*) shares a structural assumption:
it **keys on a device identity** and alarms when the *same* identifier is seen
repeatedly while moving with the victim. This assumption fails in two directions.
It is *spatial* where the true signal is *temporal*; and it is defeated outright
by identity-rotating clones — a commodity ESP32 firmware that cycles ~2,000 Find
My keys (a fresh one every ~30 s) tracked a victim for five days while raising
**zero** alerts on every tool tested.

We present **VIGIL**, an Android application that reframes the problem as
*temporal co-movement*: not "is a tracker near me" but "has *the same* tracker
been at enough of *my* distinct places, over enough time, while close enough to
be on me." VIGIL contributes (1) a cross-ecosystem, fully on-device detector that
parses five BLE wire formats and gates alerts on an RSSI proximity test that
prior tools omit, together with a learned, on-device *baseline* that auto-trusts
the trackers that live where the user lives; and (2) an identity-agnostic
**presence engine** that detects rotating-key clones by the one thing identity
churn cannot hide — an unbroken, coherent, co-moving RF *presence* — and a
**rotation-rate "squeeze"** argument showing that running an identity detector and
a churn detector together leaves no rotation rate that evades both. VIGIL holds no
network permission: the tracker database, the learned baseline, and all inference
run and remain on the device. We describe the per-ecosystem detection methodology,
the temporal model, the presence engine, the implementation, and a proposed
evaluation, and we are explicit about the adaptive-adversary floor below which
single-session on-device detection is information-theoretically impossible.

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
These tools work, in their measured evaluations, *slowly and narrowly*: an
in-the-wild study found an iPhone took 5 h 41 m to alert on an AirTag (and only on
reaching home Wi-Fi), never detected a Chipolo, and that Samsung's own app never
found its own SmartTag. All of them share the same core algorithm — **count
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
   brand-new device. In a five-day carry test it defeated iOS, Apple's Tracker
   Detect, and AirGuard's background detection.

**VIGIL** is built on the opposite premise. Where OVERWATCH (its sibling project)
asks *what is watching this place*, VIGIL asks *what has been with me* — it is a
temporal detector. Its contributions:

- **A cross-ecosystem, on-device temporal detector.** VIGIL parses Apple Find My,
  Google FMDN, Samsung SmartTag, Tile, and the unified DULT format, filters to the
  separated-from-owner state where signalled, and escalates a device only when it
  clears a **co-movement test** across the user's distinct geographic cells — gated
  by an **RSSI proximity check** that AirGuard omits, which rejects the "tracker in
  a passing car" false positive.
- **A learned, on-device baseline.** VIGIL learns the places a user dwells and
  auto-trusts trackers that recur there across days — the user's own AirTag, a
  partner's Tile — collapsing the dominant benign false-positive class without a
  server and without the user tagging anything.
- **A presence engine for rotating clones.** VIGIL detects the attack class that
  defeats every deployed tool, by abandoning identity tracking in favour of
  identity-agnostic *presence continuity*, and we give a squeeze argument
  (§5.4) that closes the evasion gap left by either detector alone.
- **A privacy architecture with no network.** VIGIL requests no `INTERNET`
  permission. There is no server, no account, and no telemetry.

We are equally explicit about what VIGIL *cannot* do (§5.6, §10): a patient,
low-duty-cycle adaptive adversary can push detection from minutes-latency
single-session to days-latency multi-session, and no on-device passive detector
escapes that floor.

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

**Defender.** The victim's Android phone, running VIGIL, **passively** scanning
BLE. It never transmits to or connects to the tracker for detection (an optional,
user-initiated GATT "make it ring" is out of the passive path). It has no server.

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
separated. VIGIL treats a cleared/absent maintained bit as separated. (Setting the
status byte to `0x00` is a known first-party-alert-suppression trick, so VIGIL
does not treat the bit as ground truth for *benign-ness* — only for *relevance*.)

**Identity.** The advertised P-224 public key **is** the identity, and — uniquely
— the BLE MAC is *derived from the key*, so both rotate together. VIGIL keys on a
hash of the advertised key bytes, which is stable across the ~24 h separated
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
| Tile | svc data `0xFEED`/`0xFEEC` | none (always findable) | **none — static MAC** | indefinite |
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
- **RSSI proximity gate** — the smoothed peak RSSI must at least once cross an
  on-body/in-bag floor (−90/−85/−80 dBm by sensitivity). **This is the piece
  AirGuard omits**, and it is what distinguishes a tracker *on* the user from one
  that merely shared a road with them at two lights.

A device escalates `OBSERVED → SUSPICIOUS → ALERTING` (`detect/CoMovementEvaluator.kt`).
ALERTING requires all four conditions; SUSPICIOUS requires repeated close sightings
without yet enough spatial/temporal spread. Non-separated (near-owner) sightings
never escalate. These thresholds are adapted from AirGuard's field-tuned model
(≥3 sightings, N distinct locations ≥150 m apart, T minutes) with the added RSSI
gate.

### 4.3 The learned baseline and the allowlist

Two trust signals suppress the dominant benign class — the trackers that *belong*
in the user's life.

- **Allowlist.** The user marks a tracker "This is mine"; it never alerts again.
- **Learned baseline** (`detect/BaselineManager.kt`). VIGIL counts location-tagged
  sightings per geohash-6 cell; a well-visited cell becomes an **anchor** (home,
  work). A tracker seen at an anchor on ≥ 3 distinct calendar days is auto-marked
  *baseline-safe* and excluded from alerting. This learns "the AirTag that is
  always in my house" without a server and without the user labelling anything.
  (The current visit-count anchor heuristic is a v1; the roadmap replaces it with
  stay-point/dwell clustering.)

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
over five days.

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

The physics that make co-movement a hard discriminator: a static tag or an
independently-moving passer can remain in the close band (radius R ≈ 10 m) for at
most `2R / v_rel` — ≈ 1.5 s at driving speed. Only a co-moving emitter (relative
velocity ≈ 0) has unbounded dwell. So a close-band presence sustained across ≥ 3
distinct cells while the user is moving *cannot* be explained by any single
ambient source.

### 5.4 The pipeline and the squeeze

A three-stage streaming pipeline (all O(1)-ish per sighting, on-device): a **CUSUM
change-point** on `λ_novel` versus a rolling personal baseline *triggers* an
identity-agnostic **presence-track confirmer** (counting seamless novel handovers
and RSSI coherence), which alarms only behind a hard **co-movement gate** (net
displacement ≥ 500 m across ≥ 3 cells while moving).

The robustness argument is the **rotation-rate squeeze.** VIGIL runs the classic
identity detector (which catches any identity persisting > ~3–5 min) *and* the
churn detector (which catches sustained novel-churn above ~0.5–1/min)
simultaneously:

```
rotation period:  30 s ─── 2 min ─── 5 min ──────────── 24 h
                  └ churn path catches ┘
                          └ overlap ┘
                                └────── identity path catches ──────┘
```

**No rotation period evades both.** Fast rotation is caught by churn; slow rotation
by identity; the middle by both. Moreover the presence-continuity path is
rotation-*rate*-agnostic — slowing rotation lowers `λ_novel` but does nothing to
the unbroken coherent co-moving track. The adversary's only real lever is
*continuity*, not rate.

### 5.5 False positives

The engine's viability rests on dense-urban and transit false positives. Four
discriminators, strongest first: (1) the **seamless-handover chain** — a crowd is
many radios and cannot fake one continuous `(t, rssi)` track across 500 m; (2)
**co-movement across turnover** — bounded ambient dwell means a presence spanning
≥ 3 non-adjacent cells is not any single source; (3) **RSSI coherence** — one
radio yields a smooth RSSI random-walk, a crowd a high-variance cloud; (4)
**stationary suppression** — no motion, no alarm, which removes the entire
apartment/at-home class. The genuinely hard case is **live rush-hour transit**,
where boarding churn, vehicular co-movement, and continuous occupancy align. The
resolution is a **mode-change gate**: a transit crowd cannot follow the victim
*off the train and down the street*, so escalation to a hard alert is withheld
until the coherent co-moving presence survives a detected context transition. This
trades **latency** for transit-FP suppression — a deliberate, disclosed choice,
not a false alarm.

### 5.6 The honest floor

Every evasion degrades the adversary's own tracker (slower/duty-cycled rotation
and reduced TX power mean fewer, coarser location fixes). Below some duty cycle — a
few randomly-timed, random-power beacons per 15 min — the emitter becomes
information-theoretically indistinguishable, *within a single session*, from sparse
ambient pass-bys, and detection must move to **cross-session recurrence** (the same
anomalous weakly-co-moving presence recurring day after day along the victim's
routes), which costs **days of latency** and requires opt-in persistent storage.
This is the honest floor: a patient, low-duty-cycle adaptive adversary forces
VIGIL from minutes-latency single-session detection to days-latency multi-session
detection. VIGIL states this rather than claiming an impossible guarantee.

---

## 6. Implementation

VIGIL is a native Android/Kotlin app (min SDK 26), reusing the scanning and
service architecture of OVERWATCH.

```
scan/TrackerSignatures.kt   BLE hex signatures for every ecosystem
scan/TrackerParser.kt       ScanResult -> TrackerObservation (five wire formats)
scan/BleTrackerScanner.kt   hardware-filtered scan (screen-off capable)
service/ScanService.kt      foreground service; geotags, persists, alerts, vibrates
data/db/VigilDatabase.kt    Room: trackers, 14-day sightings, baseline places
data/TrackerRepository.kt   ingest + baseline + evaluate on each sighting
detect/CoMovementEvaluator  the co-movement test + RSSI proximity gate
detect/BaselineManager.kt   anchor-place learning -> auto-trust household tags
detect/PresenceEngine.kt    (next) the rotation-clone engine of §5
```

**Privacy properties.** The manifest declares **no `INTERNET` permission**; VIGIL
cannot open a socket. All state — trackers, sightings, baseline, inference — lives
in an on-device SQLite database and never leaves the phone. `BLUETOOTH_SCAN` is
declared *without* `neverForLocation`, because detection is intentionally
location-correlated (and because that flag would filter out the very beacon-shaped
advertisements VIGIL parses). VIGIL only listens: it never advertises, probes, or
connects to a tracker on the detection path.

---

## 7. Proposed evaluation

VIGIL is a prototype; the following methodology is specified but **not yet
executed**, and no detection-rate claims are made here.

- **Red-team emitter.** A parameterised OpenHaystack/*Find You* ESP32 clone with
  sweepable rotation period (30 s … 24 h), TX-power variance, and duty cycle. Carried,
  it is a real co-moving emitter — ground-truth positive without endangering anyone.
- **Ambient baselines.** Raw sighting logs across environment classes (rural drive,
  suburban, dense-urban walk, bus, rush-hour subway, highway, apartment block,
  crowd), each with paired emitter-on/off runs.
- **Record/replay harness.** The presence engine is a pure
  `List<Sighting> → List<ScoredEvent>` function; ambient logs are recorded once and
  replayed to tune thresholds deterministically and offline in Kotlin, and
  attack-in-context scenarios are synthesised by merging clean and emitter-only
  traces.
- **Metrics.** True-positive rate at a fixed false-positive budget (headline:
  TPR at ≤ 1 FP per active-carry-day, per class); detection latency
  (attach → first alert); ROC/AUC; robustness curves versus rotation period, TX
  power, and duty cycle; and ablations isolating each discriminator.

---

## 8. Comparison to prior art

| Capability | Apple Tracker Detect | iOS / Android built-in | AirGuard | **VIGIL** |
|---|---|---|---|---|
| Cross-ecosystem | Find My only | AirTag + FMDN + DULT partners (not Tile) | yes | yes |
| Background / automatic | no (manual scan) | yes | yes | yes |
| Fully on-device / no account | yes | yes | yes | **yes (no network permission)** |
| RSSI proximity gate on alerts | no | undisclosed | **no** | **yes** |
| Learned baseline (auto-trust) | no | no | manual ignore only | **yes** |
| Detects rotating-key clones | no | no | no | **designed (§5)** |
| Covers Tile anti-theft | no | no | yes (static MAC) | yes |
| Survivor-grade evidence export | no | no | limited | roadmap |

Cross-ecosystem, offline operation, and temporal co-movement are *not* novel —
AirGuard has them. VIGIL differentiates on the pieces AirGuard's architecture
forecloses: the RSSI gate, the learned baseline, and above all the identity-agnostic
presence engine that targets the clone attack.

---

## 9. Privacy and ethics

VIGIL is a defensive, survivor-oriented tool. It maps and reasons about
*trackers*, never about people, and holds no network to which anything could be
exfiltrated. The learned baseline is a privacy *feature* — it exists so the app
does not need to phone home to know which tags are "normal." The dual-use surface
(BLE parsing that could in principle be repurposed for tracking) is mitigated by
the passive-only, no-transmit, no-network design. As with all anti-stalking tools,
VIGIL should be paired with survivor-safety guidance: a detected tracker can
indicate an abuser with physical access, and "make it ring / remove the battery"
advice must be given with situational-safety caveats.

---

## 10. Limitations

- **Single-session floor (§5.6).** A patient low-duty-cycle adaptive adversary is
  only detectable across sessions.
- **Near-owner and freshly-separated tags** are not passively re-linkable (Apple
  near-owner; default FMDN before the `0x41` flip; Samsung in the first 24 h).
- **Wire-format offsets** are drawn from published reverse-engineering and, for
  SmartTag2 and 2024+ Tile, inferred from older captures; they require empirical
  validation before production trust.
- **Background scan latency** is bounded by Android's ~15-min background floor
  absent a persistent foreground service; motion-triggered scanning (roadmap)
  reduces it.
- **Not yet evaluated.** §7 is a plan, not a result.

---

## 11. Related work

Heinrich et al. reverse-engineered Apple Find My ("Who Can Find My Devices?", PETS
2021) and built AirGuard ("Protecting Android Users from Stalking Attacks by Apple
Find My Devices", WiSec 2022), later measuring real-world stalking prevalence and
detector efficacy ("Please Unstalk Me", PETS 2024). Mayberry/Shafqat et al.
("Who Tracks the Trackers?" / "Track You", PETS 2023) analysed AirTag anti-stalking
timing. Yu et al. (USENIX Security 2024) reverse-engineered Samsung SmartTag, and
Georgia Tech (2025) analysed Tile's static-MAC linkability. Bräunlein/Positive
Security demonstrated the rotating-key clone ("Find You"). Naturalistic evaluations
("Stop Following Me!", EuroUSEC 2024) found deployed detectors rarely useful. The
Apple/Google **DULT** IETF effort standardises the separated-state signalling and a
baseline detection algorithm. VIGIL builds directly on this line and extends it to
the identity-rotating adversary that all of it currently misses. Change-point
detection (Page 1954; Basseville & Nikiforov 1993) and bounded-memory distinct
counting (Datar et al.; Flajolet et al.) underpin the presence engine; the
identity-free "sustained close presence" reasoning is anticipated by
contact-tracing designs (DP-3T; Google/Apple Exposure Notification).

---

## 12. Conclusion

The anti-stalking detectors people rely on are spatial where the threat is
temporal, and identity-bound where the adversary can rotate identities. VIGIL
reframes detection as temporal co-movement, adds the proximity gate and learned
baseline that make that reframing practical on-device and private, and introduces
a presence engine that detects the rotating-key clone by the one property it cannot
hide. The rotation-rate squeeze shows the combination leaves no safe rotation rate;
the honest floor shows where even this stops. We release VIGIL as a working
prototype and an open design, and invite the empirical evaluation §7 lays out.

---

## References

1. A. Heinrich, M. Stute, T. Kornhuber, M. Hollick. "Who Can Find My Devices? Security and Privacy of Apple's Crowd-Sourced Bluetooth Location Tracking System." *PoPETs* 2021. https://petsymposium.org/popets/2021/popets-2021-0045.pdf
2. A. Heinrich, N. Bittner, M. Hollick. "AirGuard — Protecting Android Users from Stalking Attacks by Apple Find My Devices." *ACM WiSec* 2022. https://arxiv.org/pdf/2202.11813 · code: https://github.com/seemoo-lab/AirGuard
3. A. Heinrich, L. Würsching, M. Hollick. "Please Unstalk Me: Understanding Stalking with Bluetooth Trackers and Democratizing Anti-Stalking Protection." *PoPETs* 2024. https://petsymposium.org/popets/2024/popets-2024-0082.pdf
4. N. Shafqat et al. "Track You: A Deep Dive into Safety Alerts for Apple AirTags." *PoPETs* 2023. https://petsymposium.org/popets/2023/popets-2023-0102.pdf
5. T. Yu et al. "Analyzing the Security of Samsung SmartTag." *USENIX Security* 2024. https://arxiv.org/pdf/2210.14702
6. Kumar, Raymaker, Specter. Tile protocol analysis. 2025. https://arxiv.org/html/2510.00350v1
7. F. Bräunlein / Positive Security. "Find You: Building a stealth AirTag clone." https://positive.security/blog/find-you · https://github.com/positive-security/find-you
8. A. Catley. "AirTag reverse engineering." https://adamcatley.com/AirTag.html
9. IETF DULT WG. "Detecting Unwanted Location Trackers" (accessory-protocol; threat-model). https://datatracker.ietf.org/wg/dult/
10. Google. "Find My Device network (Fast Pair FMDN) specification." https://developers.google.com/nearby/fast-pair/specifications/extensions/fmdn
11. SEEMOO-Lab. "OpenHaystack." https://github.com/seemoo-lab/openhaystack
12. "Stop Following Me! Evaluating the Effectiveness of Anti-Stalking Features." *EuroUSEC* 2024. https://arxiv.org/abs/2312.07157
13. E. S. Page. "Continuous Inspection Schemes." *Biometrika* 41, 1954. (CUSUM.)
14. M. Basseville, I. Nikiforov. *Detection of Abrupt Changes: Theory and Application.* 1993.
15. M. Datar, A. Gionis, P. Indyk, R. Motwani. "Maintaining Stream Statistics over Sliding Windows." *SODA* 2002.

*This working paper accompanies the VIGIL reference implementation. The rotation-clone
engine (§5) is fully specified in `docs/detection-rotation-clone.md` and is designed
but not yet wired into the shipping build.*
