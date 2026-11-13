package org.soulstone.vigil.detect

import org.soulstone.vigil.data.db.SightingEntity
import kotlin.math.cos
import kotlin.math.hypot

/**
 * What counts as a different place, for both co-movement rules.
 *
 * Two positions are different places only when they are further apart than
 * [RADIUS_M] AND than their two fixes could be wrong by, added together. So a
 * stationary device does not gain "places" because the phone's own position
 * wobbled, or flipped between Wi-Fi and a cell tower.
 *
 * The tracker rule used to count geohash-7 cells (about 150 m squares) instead.
 * Any wobble across a cell edge then made a second "place": replayed over a
 * whole evening, a separated tag next door, heard from the sofa while the
 * phone's position wandered by 25 m, alerted on High for most homes.
 */
object Places {

    /** Smallest distance at which two positions count as different places. */
    const val RADIUS_M = 300.0

    /** A fix that claims to be worse than this places nothing at all. */
    const val MAX_FIX_ACCURACY_M = 5_000f

    // Up to half the radius two fixes are as good as each other (their errors
    // together stay inside it), so those keep the order they came in.
    private const val EXACT_ENOUGH_M = (RADIUS_M / 2).toFloat()

    /** A position and how far off the phone said it might be, in metres. */
    data class Fix(val lat: Double, val lon: Double, val accuracyM: Float = 0f)

    /**
     * Where a sighting was taken, or null when there was no fix worth using.
     *
     * A fix that came with no accuracy at all (every sighting stored before
     * schema 5) is taken at face value, so only [RADIUS_M] applies to it. The
     * fused provider normally gives one.
     */
    fun fixOf(s: SightingEntity): Fix? {
        val lat = s.lat ?: return null
        val lon = s.lon ?: return null
        val acc = s.accuracy ?: 0f
        return if (acc <= MAX_FIX_ACCURACY_M) Fix(lat, lon, acc) else null
    }

    /** True when both sightings have a usable fix and the two are different places. */
    fun movedOn(from: SightingEntity, to: SightingEntity): Boolean {
        val a = fixOf(from) ?: return false
        val b = fixOf(to) ?: return false
        return apart(a, b)
    }

    /**
     * True when two positions are different places: further apart than
     * [RADIUS_M] and than the two fixes' claimed errors added together.
     */
    fun apart(a: Fix, b: Fix): Boolean =
        metres(a.lat, a.lon, b.lat, b.lon) > sameWithin(a, b)

    private fun sameWithin(a: Fix, b: Fix): Double =
        maxOf(RADIUS_M, (a.accuracyM + b.accuracyM).toDouble())

    /**
     * Number of distinct places in a time-ordered list of positions.
     *
     * Each position joins the nearest existing place it cannot be told apart from,
     * or founds a new one. "Cannot be told apart" means closer to that place's
     * founding position than [RADIUS_M], or than the two fixes' claimed errors
     * added together, whichever is larger.
     *
     * Vague positions get their turn after the exact ones, vaguest last, whatever
     * the order they arrived in. A cell-tower fix good to 2 km, taken as the phone
     * woke up, cannot be told apart from anywhere within 2 km. If it founded a
     * place, every exact fix of the journey that followed joined it, and a device
     * carried across three neighbourhoods had been to "one place".
     *
     * A place then has to be believable at least once. A visit that is a single
     * position is not believable when the positions around it show the device was
     * dwelling somewhere else: A, B, A in the middle; A, A, B at the end (the next
     * position will say whether B was real); B, A, A at the start. A place whose
     * every visit is like that is dropped, however often it recurs, because a
     * phone's position does jump to the same wrong spot again and again. Two
     * positions in a row at a place make it real. So does passing through: on a
     * journey every position is a different place (A, B, C, D), none of those
     * patterns occurs, and nothing is dropped.
     *
     * What this does not catch: a position that is wrong twice running (A, X, X, A),
     * or wrong in two different ways running (A, X, Y, A). Either is exactly what a
     * short trip out and back looks like, and that trip is the thing being looked
     * for. Fixes that admit to their error are handled above; these are fixes
     * that are far off while claiming to be good.
     */
    fun distinct(points: List<Fix>): Int {
        if (points.isEmpty()) return 0
        val leaders = ArrayList<Fix>()
        val seq = IntArray(points.size)
        val byExactness = points.indices.sortedBy { maxOf(points[it].accuracyM, EXACT_ENOUGH_M) }
        for (i in byExactness) {
            val p = points[i]
            var best = -1
            var bestD = Double.MAX_VALUE
            for ((c, l) in leaders.withIndex()) {
                val d = metres(l.lat, l.lon, p.lat, p.lon)
                if (d <= sameWithin(l, p) && d < bestD) {
                    best = c
                    bestD = d
                }
            }
            if (best < 0) {
                leaders.add(p)
                best = leaders.size - 1
            }
            seq[i] = best
        }
        val n = seq.size
        if (n < 3) return leaders.size
        val believable = BooleanArray(leaders.size)
        var i = 0
        while (i < n) {
            var end = i
            while (end + 1 < n && seq[end + 1] == seq[i]) end++
            val ok = when {
                end > i -> true                          // stayed for two positions or more
                i == 0 -> seq[1] != seq[2]               // B, A, A
                i == n - 1 -> seq[n - 2] != seq[n - 3]   // A, A, B
                else -> seq[i - 1] != seq[i + 1]         // A, B, A
            }
            if (ok) believable[seq[i]] = true
            i = end + 1
        }
        return believable.count { it }
    }

    /** Ground distance in metres; the flat-earth form is exact enough at this scale. */
    fun metres(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val mPerDegLat = 111_320.0
        var dLon = lon2 - lon1
        if (dLon > 180) dLon -= 360 else if (dLon < -180) dLon += 360
        val dy = (lat2 - lat1) * mPerDegLat
        val dx = dLon * mPerDegLat * cos(Math.toRadians((lat1 + lat2) / 2))
        return hypot(dx, dy)
    }
}
