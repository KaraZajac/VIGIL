# VIGIL — Detecting Key-Rotating Tracker Clones by Presence, Not Identity

**Design document · the core novel detection algorithm (problem #1) · v1 · 2026-07-14**

Target: on-device Android (Kotlin), no server. Slots into VIGIL's `scan/` +
`detect/` architecture; the co-movement v1 in `detect/CoMovementEvaluator.kt`
ships now, the presence engine below (`detect/PresenceEngine.kt`) is next.

---

## 0. Thesis (TL;DR)

Every deployed anti-stalking detector — Apple *Tracker Detect*, iOS *Find My*
unwanted-tracking alerts, and TU-Darmstadt's **AirGuard** — keys on **device
identity**: it counts repeat sightings of one BLE identifier and alarms at a
threshold (AirGuard: the *same* identifier seen **≥ 3 times across a location
change**). A key-rotating clone (Positive Security's **Find You**: an ESP32
cycling **2000** Find My public keys, a new one every **~30 s**) presents each
sighting as a brand-new device, so the repeat count never accumulates. In the
authors' 5-day carry test, **no tool raised any passive alert.**

VIGIL's differentiator: **stop tracking identities, start tracking the *presence*.**
Ask *"is there one physical radio maintaining an unbroken, close-range, co-moving
RF presence, even though no single identifier survives?"* This is detectable
because the attack, to function as a tracker, must emit an **unbroken stream of
separated-state beacons at roughly stable close-range RSSI that moves with the
victim across locations where the ambient tracker population fully turns over.**
Identity churn cannot hide that.

The most exploitable fact: **the standards mandate slow rotation.** IETF DULT,
Apple, and Google all specify that a separated accessory rotates its address/key
**once per 24 hours** (AirTag: static MAC+key for 24 h, re-rolling at 04:00; DULT
accessory-protocol: rotate every 24 h; Google FMDN: 24 h in unwanted-tracking
mode). A real separated tracker following you produces **one identity for a full
day**. A clone rotating every 30 s runs **~2880× faster than any spec-compliant
device** — not "suspicious," but *categorically impossible* for a legitimate
separated tracker. VIGIL anomaly-detects against that standardized baseline.

---

## 1. Formalization: the observable signal

### 1.1 Per-sighting record

```
Sighting {
  t, wallT   : Long        // monotonic + epoch ms
  eco        : Ecosystem   // APPLE_FM | GOOGLE_FMDN | SAMSUNG_ST | TILE
  eid        : ByteArray   // ephemeral identity (§1.2) — the churning label
  separated  : Boolean     // separated/lost-state beacon? (§1.3)
  rssi       : Int         // dBm
  txPower    : Int?        // advertised TX power if present (path-loss norm.)
  lat,lon    : Double; gpsAcc, speed : Float   // last fused fix + speed
}
```

### 1.2 The identity that churns (`eid`)

| Ecosystem | `eid` | Spec rotation (separated) |
|---|---|---|
| Apple Find My | 28-byte public key (MAC ⧺ payload key bytes); MAC as cheap proxy | **24 h** (re-roll 04:00) |
| Google FMDN | rotating EID (`Rx`) | ~1024 s normal; **24 h** unwanted-tracking mode |
| Samsung SmartTag | rotating region of offline-finding payload | ~15 min–24 h |
| Tile | rotating id (but MAC is static) | vendor-specific |

Structural fact for Apple: **the MAC is derived from the key, so both rotate
together** — you cannot even fall back to MAC-tracking. Identity churn is total.

### 1.3 The `separated` predicate

Only separated-state beacons are relevant: a tracker with its owner is
nearby/connectable (no threat). A tracker following you without its owner is by
definition separated and broadcasts its full key every 2 s. VIGIL filters to
`separated == true` — this cuts noise and is exactly the population the clone
must join.

### 1.4 The three core computable quantities

Close band: `rssi ≥ R_close` (starter **−65 dBm**).

**(a) Novel-identifier churn rate `λ_novel`.** Keep a set `Seen` of `eid`s over
horizon `H` (30 min). A sighting is *novel* if `eid ∉ Seen`.
`λ_novel(t) = |distinct eid first seen in (t−W, t]| / W` (W = 5 min).
Real separated tracker → ~0.0007/min. Find You @30 s → ~2/min. A ~2800× gap.

**(b) Presence continuity `PC`** — is the close band *continuously occupied*,
regardless of *who* occupies it?
`o(t)=1 iff ∃ separated sighting in (t−δ,t] with rssi≥R_close` (δ = 10 s);
`PC(W) = duty cycle of o`; `Runmax = longest run with no gap > g_break` (20 s).
Clone → `PC≈1`, `Runmax` = whole session. A crowd also keeps it occupied, so
`PC` alone is insufficient — it must be tied to coherence (§1.5) and co-movement.

**(c) Co-movement `CM`** — did the presence follow you across a **population
turnover**? The physics that makes this a *hard* discriminator — the **ambient
dwell bound**: a fixed tag or a passer stays in your close band (radius R≈10 m)
for at most `dwell ≤ 2R / v_rel`. At 13 m/s (driving) → ~1.5 s; walking → ~14 s;
only a co-*moving* emitter (v_rel≈0) has unbounded dwell. So **conditioned on
measured v̄ > 0, a close-band presence whose dwell ≫ 2R/v̄ is co-moving by
construction.** Over K ≥ 3 non-adjacent cells at speed, no single ambient tag
can appear — the presence is provably not any one ambient source.

### 1.5 The unifying reframe: presence tracking without identity

Discard `eid` as a *tracking* key; keep it only as a *novelty* signal. Treat
close-band separated sightings as points in `(t, rssi)` and ask: does **one
continuous, low-variance trajectory** explain them (one radio), or a **diffuse
high-variance cloud** (a crowd)? At each **handover** (close-band identity
changing) test continuity:

```
seamless handover:  |rssi_new − ewma_rssi| ≤ ε_rssi (8 dB)  AND  Δt ≤ g_seam (6 s)
```

A rotating clone emits a **long chain of seamless handovers into novel
identities** — the radio never leaves, only its name does. A crowd emits gappy,
RSSI-discontinuous handovers. This **seamless-novel-handover chain** is the
algorithmic heart of VIGIL and the quantity no identity churn can fake while
remaining a functioning tracker.

---

## 2. Algorithm

### 2.1 Weighing four candidate approaches

| Approach | Catches clone? | Cost | FP in dense RF | Verdict |
|---|---|---|---|---|
| Sliding-window novelty count | Yes | Trivial | Bad (busy street churns) | cheap *trigger* only |
| CUSUM change-point on `λ_novel` | Yes, bounded latency | Trivial | Better (regime vs baseline) | good *trigger*, must gate |
| Density on `(rssi,t)` | Yes | Heavier | Separates single-radio track from crowd | do the online single-track version |
| Presence-occupancy + seamless chain | Yes | Moderate O(1)/sighting | **Best** (coherence + co-move) | **core confirmer** |

None alone is deployable. The design is a **hybrid pipeline**: a cheap CUSUM
churn *trigger* wakes an expensive presence-coherence *confirmer*, which only
alarms behind a *co-movement gate*.

### 2.2 Pipeline

```
Stage A  CUSUM on λ_novel (always on, O(1))         → trigger
Stage B  presence-track confirmer (armed on trigger): identity-agnostic single
         (t,rssi) track; counts novel *seamless* handovers, RSSI residual σ,
         presence-run duration                        → candidate
Stage C  co-movement gate + scoring: requires Dnet, distinct cells, v̄>0 → 0–100
```

### 2.3 / 2.4 State + update rule

```kotlin
class ChurnCusum(val mu0: Double, val sigma0: Double, val k: Double, val h: Double) {
  var S = 0.0
  fun update(novelInLastMin: Int): Boolean {          // per minute
    val z = (novelInLastMin - mu0) / sigma0
    S = maxOf(0.0, S + z - k)
    return S > h                                        // sustained regime shift
  }
}

data class PresenceRun(
  var startT: Long, var startLat: Double, var startLon: Double,
  var lastT: Long, var lastEid: ByteArray,
  var ewmaRssi: Double, var ewmaAbsResid: Double,       // coherence
  var handovers: Int, var seamlessNovelHandovers: Int,
  var cells: MutableSet<Long>, var dnet: Double, var vSum: Double, var vN: Int,
  var active: Boolean
)

fun onSeparatedSighting(s: Sighting, st: State) {
  val novel = !st.seen.contains(s.eid); if (novel) st.seen.add(s.eid)
  st.novelThisMinute += if (novel) 1 else 0
  if (s.rssi < R_CLOSE) return                          // only close band builds presence
  val p = st.run
  if (!p.active || s.t - p.lastT > G_BREAK) {           // (re)start a run
     if (p.active) evaluate(p, st)
     st.run = PresenceRun(s.t, s.lat, s.lon, s.t, s.eid, s.rssi.toDouble(), 0.0,
        0, 0, mutableSetOf(cellOf(s.lat,s.lon)), 0.0, s.speed.toDouble(), 1, true)
     return
  }
  val resid = s.rssi - p.ewmaRssi
  if (!s.eid.contentEquals(p.lastEid)) {                // handover
     p.handovers++
     if (abs(resid) <= EPS_RSSI && (s.t - p.lastT) <= G_SEAM && novel)
        p.seamlessNovelHandovers++                      // ← THE tell
  }
  p.ewmaRssi     = ALPHA*s.rssi + (1-ALPHA)*p.ewmaRssi
  p.ewmaAbsResid = ALPHA*abs(resid) + (1-ALPHA)*p.ewmaAbsResid   // ≈ RSSI σ
  p.lastT = s.t; p.lastEid = s.eid
  p.cells.add(cellOf(s.lat,s.lon)); p.dnet = haversine(p.startLat,p.startLon,s.lat,s.lon)
  p.vSum += s.speed; p.vN++
  scoreLive(p, st)
}

fun scoreLive(p: PresenceRun): Int {
  val durMin = (p.lastT - p.startT)/60_000.0; val vbar = p.vSum/p.vN
  val moving  = vbar > V_MIN                             // 1 m/s
  val comoves = p.dnet >= D_NET && p.cells.size >= K_CELLS       // 500 m & 3 cells
  if (!(moving && comoves)) return 0                     // Stage-C gate: silent
  val coherent = p.ewmaAbsResid <= SIGMA_MAX             // single-radio tightness
  val churnAnom = (p.seamlessNovelHandovers/max(durMin,1e-3)) >= RHO_NOVEL
  val seamlessFrac = if (p.handovers>0) p.seamlessNovelHandovers.toDouble()/p.handovers else 0.0
  var sc = 0.0
  sc += clamp(durMin/T_MIN,0.0,1.0)*30
  sc += clamp(p.seamlessNovelHandovers/N_HANDOVER,0.0,1.0)*30
  sc += clamp(p.cells.size/(2.0*K_CELLS),0.0,1.0)*20
  sc += (if (coherent) 1.0 else 0.3)*(if (churnAnom) 1.0 else 0.5)*(0.5+0.5*seamlessFrac)*20
  return sc.roundToInt()
}
```

Tiers (VIGIL 4-tier): `<40` clear · `40–69` WATCHING (soft) · `70–84` probable
clone · `85+` persisted across ≥ K_CELLS cells / mode change.

### 2.5 Starter parameters

`R_CLOSE −65 dBm · δ 10 s · G_BREAK 20 s · G_SEAM 6 s · EPS_RSSI 8 dB · SIGMA_MAX
8 dB · H 30 min · W 5 min · CUSUM k 0.5, h 5, μ0/σ0 = rolling personal baseline ·
α 0.3 · ρ (min novel-seamless rate) 0.7/min · D_NET 500 m · K_CELLS 3×250 m ·
V_MIN 1 m/s · T_MIN 10 min (ORANGE)/3 min (WATCHING) · N_HANDOVER 12`.

### 2.6 Fit to VIGIL

Runs entirely on-device over the existing scan path. `scan/TrackerParser.kt`
already emits per-ecosystem separated sightings; add speed to the record via
`LocationProvider`. New `detect/PresenceEngine.kt` holds `ChurnCusum` +
`PresenceRun` + the novelty set as a pure streaming function (offline-testable,
§5). Keep a bounded ring buffer over horizon `H`; multi-session mode (§4.3) is
opt-in.

---

## 3. False positives (the crux)

The enemy is dense urban RF where legitimate separated-Find-My churn is naturally
high. FPs are where this feature lives or dies.

| Scenario | Fires | Killed by |
|---|---|---|
| Dense-urban walk / festival | high λ_novel, PC≈1 | **seamless chain fails** — a crowd is many radios ⇒ gappy, RSSI-discontinuous handovers, high ewmaAbsResid |
| Apartment / at home | high PC, stationary | **co-movement gate** (v̄≈0, 1 cell) ⇒ score 0 |
| One stranger's real AirTag on your bus | co-moving, close, stable | not the clone path (it has 1 id/24 h) — handled by the identity detector + dismiss/whitelist UX |
| Busload of strangers' real tags | co-moving, PC≈1 | **novel-churn ≈ 0** (stable set of stable ids) ⇒ clone path silent |
| Rush-hour subway w/ heavy boarding churn | A + B + partial C | **the genuinely hard case — §3.3** |

**Discriminators, strongest first:** (1) seamless-novel-handover chain — only one
physical emitter yields `|Δrssi|≤8 dB, Δt≤6 s` across identity changes,
repeatedly; (2) co-movement across turnover (dwell bound); (3) RSSI coherence;
(4) stationary suppression (removes the whole home/apartment class).

**Where it breaks: live rush-hour transit.** People board/alight with real
separated tags, so λ_novel is high, the vehicle co-moves you across cells, PC≈1 —
three signals align. What still separates it: those tags are at many distances
(high ewmaAbsResid, low seamless fraction) and turnover is bursty/stop-synced, not
a 30 s metronome. The decisive gate: **persistence across a mode change** — a
transit crowd cannot follow you *off the train and down the street*. Requiring the
coherent co-moving presence to survive a context transition drops transit FPs to
~0, **at the cost of latency**. That is the honest central trade: lower transit FP
is bought with detection latency. The MVP keeps transit at WATCHING (silent) and
escalates only after a mode change or ≥ T_MIN beyond the transit segment.

---

## 4. Robustness

### 4.1 The rotation-rate squeeze (the strongest claim)

VIGIL runs the **identity path** (classic AirGuard-style; catches any key held
> ~3–5 min, seen ≥3× across a location change) **and** the **churn path** (this
doc; catches novel-seamless rates above ~0.5–1/min) together:

```
rotation period:  30s ──── 2min ──── 5min ─────────── 24h
                  └ churn path ┘
                        └ overlap seam ┘
                              └────── identity path ──────┘
```

**No rotation period evades both.** Fast → churn path; slow → identity path;
middle → both. Moreover the presence-continuity/co-movement path is
rotation-rate-agnostic — slowing rotation lowers λ_novel but does nothing to the
unbroken coherent co-moving close-band track. **The attacker's only real lever is
continuity, not rate.**

### 4.2 Adaptations

| Move | Effect | Response | Residual |
|---|---|---|---|
| Slower rotation | λ_novel drops | identity path re-engages | high (squeeze) |
| Variable TX power | RSSI jitter weakens coherence | normalize by advertised txPower; lean on continuity+co-move+churn | med–high |
| Intermittent duty-cycling | gaps reset runs | reacquire logic: a gap < G_reacq (3 min) resuming at coherent RSSI along your path = same presence; accumulate across gaps | medium (this is the biting attack) |
| Blend into transit | crowd cover | mode-change gate (§3.3) | medium (needs latency) |

### 4.3 The true floor

Every evasion degrades the attacker's own tracker (slower/duty-cycled = fewer
fixes; lower power = shorter range). Below some duty cycle (a few random beacons
per 15 min, random power) the emitter is information-theoretically
indistinguishable from sparse ambient pass-bys **in a single session**, and
detection must move to **cross-session recurrence** — the same anomalous weakly-
co-moving presence recurring day after day along your routes (a per-day coarse
RSSI/timing fingerprint matched across sessions). That trades **days of latency**
and needs opt-in persistent storage. **The honest floor: a patient, low-duty-cycle
adaptive attacker pushes VIGIL from minutes-latency single-session detection to
days-latency multi-session detection.** Say so; don't claim an impossible
guarantee.

---

## 5. Validation plan (on-device Kotlin, no server, no real stalker)

1. **Red-team emitter.** Parameterized OpenHaystack / Find You ESP32 clone
   (base: `positive-security/find-you`), sweeping `rotationPeriod ∈ {30 s … 24 h}`,
   `txPower ∈ {fixed, ±k dB}`, `dutyCycle ∈ {100%, 50%, 20%, bursty}`,
   `keyPoolSize`. Carried, it is a real co-moving emitter → ground-truth-positive
   without endangering anyone.
2. **Ambient baselines** (ground-truth-negative), by class: rural drive · suburban
   · dense-urban walk · bus · subway/rush-hour · highway · apartment · café. Paired
   runs (same route, emitter on/off).
3. **Record/replay harness** — make `PresenceEngine` a pure
   `List<Sighting> → List<ScoredEvent>`; record raw sightings once, replay JSONL to
   A/B thresholds deterministically offline (unit tests / in-app debug screen).
   Synthesize attack-in-context by merging a clean run with an emitter-only trace.
4. **Metrics:** TPR @ ≤ 1 FP / active-carry-day per class; detection latency
   (attach → first ORANGE), target ≤ 10–15 min for default Find You; ROC/AUC;
   robustness curves (TPR vs rotationPeriod / txPower / dutyCycle); ablations
   (churn-only → +coherence → +co-move gate → full).

---

## 6. Verdict

**Feasible enough to be VIGIL's headline — for a precisely scoped claim, with
honest caveats.**

Real, not hype: the attack must maintain a continuous coherent co-moving presence
to function; the standards mandate 24 h rotation, giving a *categorical* anomaly
baseline; the identity×churn squeeze leaves no safe rate; and VIGIL strictly
dominates incumbents on this attack (they raise zero alerts on Find You, VIGIL
raises one). Research-grade long shots: robust low-FP detection in live rush-hour
transit, and a patient low-duty-cycle adaptive attacker (provably forced to
multi-session/days latency).

**Minimum viable version (ship first).** Target the published Find You config
(30 s, fixed power, ~100% duty) — the exact attack that renders AirGuard silent:
Stage A CUSUM trigger; Stage B presence track counting novel *seamless*
handovers with RSSI coherence; Stage C hard co-movement gate (ORANGE after ≥10
min coherent co-moving presence, ≥12 novel seamless handovers, ≥3 cells while
v̄>1 m/s; RED after a cell/mode change). Run the classic identity detector in
parallel. New tier distinct from the identity-based alert, with a dismiss/whitelist
UX for the stranger-on-your-commute case. Detects the real-world clone in ~10–15
min at a near-zero FP rate outside rush-hour transit — and is honest about the
adaptive-attacker floor.

---

## 7. References

- Positive Security, "Find You: Building a stealth AirTag clone" — https://positive.security/blog/find-you · https://github.com/positive-security/find-you
- Heinrich et al., "Who Can Find My Devices?" PETS 2021 — https://petsymposium.org/popets/2021/popets-2021-0045.pdf
- Heinrich et al., "AirGuard", WiSec 2022 — https://arxiv.org/pdf/2202.11813
- A. Catley, AirTag reverse engineering — https://adamcatley.com/AirTag.html
- IETF DULT — accessory-protocol + threat-model — https://datatracker.ietf.org/wg/dult/
- Google Fast Pair FMDN spec — https://developers.google.com/nearby/fast-pair/specifications/extensions/fmdn
- SEEMOO OpenHaystack — https://github.com/seemoo-lab/openhaystack
- E. S. Page, "Continuous Inspection Schemes" (Biometrika 1954) — CUSUM; Basseville & Nikiforov, *Detection of Abrupt Changes* (1993)
- Datar/Gionis/Indyk/Motwani sliding-window distinct counting; Flajolet et al. HyperLogLog
- Becker et al., "Tracking Anonymized Bluetooth Devices", PETS 2019
- DP-3T / Google-Apple Exposure Notification — RSSI attenuation-bucket presence reasoning
