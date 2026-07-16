package org.soulstone.vigil.model

/** The tracker ecosystems VIGIL parses off the air. */
enum class TrackerEcosystem(val display: String) {
    APPLE_FIND_MY("Apple Find My"),
    GOOGLE_FMDN("Google Find My Device"),
    SAMSUNG_SMARTTAG("Samsung SmartTag"),
    TILE("Tile"),
    DULT("DULT tracker"),
    UNKNOWN("Unknown tracker")
}

/**
 * Whether a tracker is signalling it is away from its owner. SEPARATED is the
 * detectable/dangerous state (slow ID rotation, reportable payload). Tile has no
 * separated flag, so Tile sightings are UNKNOWN and rely on persistence alone.
 */
enum class SeparatedState { SEPARATED, NEAR_OWNER, UNKNOWN }

/** One parsed BLE sighting of a tracker at one instant. */
data class TrackerObservation(
    /** Best-effort stable identity within a rotation epoch (payload key / static MAC). */
    val stableId: String,
    val ecosystem: TrackerEcosystem,
    val mac: String,
    val rssi: Int,
    val separated: SeparatedState,
    val label: String,
    val timestampMs: Long = System.currentTimeMillis()
)

/** Risk lifecycle for a tracked device (hysteretic; see CoMovementEvaluator). */
enum class RiskState { OBSERVED, SUSPICIOUS, ALERTING }

/** User-facing status, folding in the allowlist + learned baseline. */
enum class TrackerStatus { SAFE_APPROVED, SAFE_BASELINE, OBSERVED, SUSPICIOUS, ALERTING }

/** Detection sensitivity — trades time-to-alert against false positives. */
enum class Sensitivity { HIGH, MEDIUM, LOW }

/** A geotagged point where a tracker was seen, for the in-app co-movement trail. */
data class TrailPoint(val lat: Double, val lon: Double)
