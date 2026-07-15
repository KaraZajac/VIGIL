package org.soulstone.vigil.util

/**
 * Minimal geohash encoder. Used to bucket sightings into "distinct places" for
 * the co-movement test and to anchor baseline locations.
 *
 * Precision reference: length 7 ≈ 153 m × 153 m cell; length 6 ≈ 1.2 km × 0.6 km.
 * VIGIL uses length 7 for "distinct place" counting and length 6 for baseline anchors.
 */
object Geohash {
    private const val BASE32 = "0123456789bcdefghjkmnpqrstuvwxyz"

    fun encode(lat: Double, lon: Double, precision: Int = 7): String {
        var latMin = -90.0; var latMax = 90.0
        var lonMin = -180.0; var lonMax = 180.0
        val sb = StringBuilder(precision)
        var bit = 0
        var ch = 0
        var even = true
        while (sb.length < precision) {
            if (even) {
                val mid = (lonMin + lonMax) / 2
                if (lon >= mid) { ch = ch or (1 shl (4 - bit)); lonMin = mid } else { lonMax = mid }
            } else {
                val mid = (latMin + latMax) / 2
                if (lat >= mid) { ch = ch or (1 shl (4 - bit)); latMin = mid } else { latMax = mid }
            }
            even = !even
            if (bit < 4) {
                bit++
            } else {
                sb.append(BASE32[ch]); bit = 0; ch = 0
            }
        }
        return sb.toString()
    }
}
