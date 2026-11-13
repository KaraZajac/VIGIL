package org.soulstone.vigil.eval

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.soulstone.vigil.model.Sensitivity
import org.soulstone.vigil.model.TrackerEcosystem
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Whole days of the tracker (identity) path through [ReplayHarness]: a separated
 * tag advertising every 2 s, recorded at most every 15 s, judged over its last
 * 24 h by the real [org.soulstone.vigil.detect.CoMovementEvaluator].
 *
 * These pin down the logic on simulated days. They are not field data: the radio
 * is perfect here, and every advert in range is heard.
 *
 * With the previous rule (geohash-7 cells, counting by the clock alone), a tag in a
 * car on commutes under 15 minutes was first flagged a day later, and a separated
 * tag next door alerted on High in most homes once the phone's position wandered
 * by 25 m. Both are asserted against below.
 */
class TrackerDayEvalTest {

    private val min = 60_000L
    private fun hm(day: Int, h: Int, m: Int = 0) = (day * 1440L + h * 60 + m) * min

    /** A tag advertising every 2 s from [from] to [to], at the positions [where] gives. */
    private fun tag(
        id: String, from: Long, to: Long, rssi: Int,
        where: (Long) -> NearbyReplay.Pos
    ): List<SimTraces.SimSighting> {
        val out = ArrayList<SimTraces.SimSighting>()
        var ts = from
        while (ts <= to) {
            val p = where(ts)
            out += SimTraces.SimSighting(id, rssi, ts, p.lat, p.lon)
            ts += 2_000L
        }
        return out
    }

    /** A drive of [minutes] from [fromKm] to [toKm] north, at 36 km/h-ish. */
    private fun drive(id: String, start: Long, minutes: Int, fromKm: Double, toKm: Double) =
        tag(id, start, start + minutes * min, -60) { ts ->
            val f = (ts - start).toDouble() / (minutes * min)
            NearbyReplay.at(fromKm + (toKm - fromKm) * f)
        }

    @Test fun aTagInTheCarIsFlaggedOnTheFirstDriveHome() {
        // Heard only in the car: morning and evening drives, departures varying by
        // up to 20 minutes either way. However short the drive, the first drive
        // home flags it, because each drive counts as it moves on.
        for (d in listOf(4, 8, 14, 30)) {
            for (sched in 0 until 8) {
                val rng = Random(1000 + sched)
                val trace = (0 until 2).flatMap { day ->
                    val am = hm(day, 8) + (rng.nextInt(41) - 20) * min
                    val pm = hm(day, 17) + (rng.nextInt(41) - 20) * min
                    drive("car", am, d, 0.0, d * 0.6) + drive("car", pm, d, d * 0.6, 0.0)
                }
                for (s in Sensitivity.entries) {
                    val at = ReplayHarness.run(trace, s, TrackerEcosystem.APPLE_FIND_MY).identityAlertMs
                    assertNotNull("$d min, schedule $sched, $s: never flagged", at)
                    assertTrue(
                        "$d min, schedule $sched, $s: flagged at ${at!! / min} min",
                        at in hm(0, 16, 40)..hm(0, 17, 21)
                    )
                }
            }
        }
    }

    @Test fun aTagInABagIsFlaggedOnceTheTimeIsUp() {
        // With you from 08:00: a 20-minute walk in, the morning at the office, a
        // walk at lunch. The time thresholds (30 / 45 / 90 min) decide.
        val walkIn = tag("bag", hm(0, 8), hm(0, 8, 20), -55) { ts ->
            NearbyReplay.at((ts - hm(0, 8)).toDouble() / (20 * min) * 1.5)
        }
        val office = tag("bag", hm(0, 8, 20), hm(0, 12), -55) { NearbyReplay.at(1.5) }
        val lunch = tag("bag", hm(0, 12), hm(0, 12, 15), -55) { ts ->
            NearbyReplay.at(1.5 + (ts - hm(0, 12)).toDouble() / (15 * min) * 0.5)
        }
        val trace = walkIn + office + lunch
        val expected = mapOf(Sensitivity.HIGH to hm(0, 8, 30), Sensitivity.MEDIUM to hm(0, 8, 45), Sensitivity.LOW to hm(0, 9, 30))
        for ((s, at) in expected) {
            // Within the minute: sightings are written every 16 s (2 s adverts, 15 s throttle).
            val got = ReplayHarness.run(trace, s, TrackerEcosystem.APPLE_FIND_MY).identityAlertMs
            assertNotNull("$s: never flagged", got)
            assertTrue("$s: flagged at ${got!! / 1000} s", got in at until at + min)
        }
    }

    @Test fun aSeparatedTagNextDoorNeverAlertsHoweverThePositionWobbles() {
        // Heard from the sofa 18:00-21:00 while the phone's fix wanders up to
        // [wobbleM] from the truth, a new fix every 15 s. With the old rule, 25 m
        // was enough for most homes to alert on High, and 100 m for every home at
        // every setting.
        for (wobbleM in listOf(25.0, 100.0)) {
            for (home in 0 until 6) {
                val rng = Random(home)
                val base = NearbyReplay.at(rng.nextDouble() * 2, rng.nextDouble() * 2)
                var fix = base
                var fixAt = -1L
                val trace = tag("next-door", hm(0, 18), hm(0, 21), -78) { ts ->
                    if (ts - fixAt >= 15_000L) {
                        val r = wobbleM * sqrt(rng.nextDouble())
                        val a = rng.nextDouble() * 2 * Math.PI
                        fix = NearbyReplay.Pos(
                            base.lat + r * cos(a) / 111_320.0,
                            base.lon + r * sin(a) / (111_320.0 * cos(Math.toRadians(base.lat)))
                        )
                        fixAt = ts
                    }
                    fix
                }
                for (s in Sensitivity.entries) {
                    val o = ReplayHarness.run(trace, s, TrackerEcosystem.APPLE_FIND_MY)
                    assertEquals("wobble $wobbleM m, home $home, $s", 0, o.identityAlerts)
                }
            }
        }
    }
}
