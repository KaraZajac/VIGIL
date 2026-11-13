package org.soulstone.vigil.eval

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.soulstone.vigil.eval.NearbyReplay.Leg
import org.soulstone.vigil.eval.NearbyReplay.Pos
import org.soulstone.vigil.eval.NearbyReplay.Source
import org.soulstone.vigil.eval.NearbyReplay.at
import org.soulstone.vigil.eval.NearbyReplay.carried
import org.soulstone.vigil.eval.NearbyReplay.daily
import org.soulstone.vigil.eval.NearbyReplay.fixed
import org.soulstone.vigil.eval.NearbyReplay.rotating
import org.soulstone.vigil.eval.NearbyReplay.route
import org.soulstone.vigil.model.RiskState
import org.soulstone.vigil.model.Sensitivity
import org.soulstone.vigil.model.Sensitivity.HIGH
import org.soulstone.vigil.model.Sensitivity.LOW
import org.soulstone.vigil.model.Sensitivity.MEDIUM

/**
 * What the "any other Bluetooth device" check catches, what it flags by mistake,
 * and what it misses, as whole days replayed through [NearbyReplay].
 *
 * These are scenarios, not field data. They pin down the LOGIC: a threshold change
 * that stops a tracker in a car being caught, or starts flagging a neighbour's
 * television, fails here. They say nothing about how often each scenario happens
 * in a real street, or how a real radio behaves. The false alarms and misses
 * below are asserted on purpose, so the app's copy can be held to them.
 *
 * Every scenario runs at both sweep phases (starting on an even and on an odd
 * minute), so no result depends on a sweep happening to land on the right minute.
 */
class NearbyDeviceEvalTest {

    private val home = at(0.0)
    private val day = 1440L
    private fun hm(h: Int, m: Int = 0, onDay: Int = 1) = (onDay - 1) * day + h * 60L + m

    private fun clock(t: Long?) =
        t?.let { "day %d %02d:%02d".format(it / day + 1, it % day / 60, it % 60) } ?: "never"

    /** First alert for [id] at each sweep phase. */
    private fun firstAlerts(
        s: Sensitivity,
        legs: List<Leg>,
        sources: List<Source>,
        days: Int,
        id: String,
        gps: (Long, Pos?) -> Pos? = { _, p -> p }
    ): List<Long?> = (0L..1L).map { phase ->
        NearbyReplay.run(route(home, legs), sources, days * day, s, phase, gps).firstAlert("ble:$id")
    }

    private fun assertAlertsBetween(from: Long, to: Long, got: List<Long?>, what: String) {
        for (t in got) {
            assertNotNull("$what: never alerted", t)
            assertTrue("$what: alerted ${clock(t)}, expected ${clock(from)} to ${clock(to)}", t!! in from..to)
        }
    }

    private fun assertNever(got: List<Long?>, what: String) {
        for (t in got) assertNull("$what: alerted ${clock(t)}", t)
    }

    // --- a tracker hidden in the car --------------------------------------

    /** Drive to work at 08:00 and home at 17:00, [minutes] each way at 40 km/h. */
    private fun commute(minutes: Long, days: Int): List<Leg> {
        val work = at(40.0 * minutes / 60)
        return daily(listOf(Leg(hm(8), hm(8) + minutes, home, work), Leg(hm(17), hm(17) + minutes, work, home)), days)
    }

    private fun whileDriving(legs: List<Leg>) = { t: Long -> legs.any { t >= it.startMin && t < it.endMin } }

    @Test fun aTrackerInTheCarIsFlaggedOnTheDriveHome() {
        // Heard only while driving: the car is parked out of earshot at both ends.
        for (minutes in listOf(12L, 20L, 30L, 45L)) {
            val legs = commute(minutes, days = 1)
            val car = listOf(carried("car", -65, whileDriving(legs)))
            for (s in Sensitivity.entries) {
                assertAlertsBetween(hm(17), hm(17, 2), firstAlerts(s, legs, car, 1, "car"), "$minutes min, $s")
            }
        }
    }

    @Test fun shortDrivesNeedAHigherSensitivity() {
        val eight = commute(8, days = 3)
        val carOn8 = listOf(carried("car", -65, whileDriving(eight)))
        assertAlertsBetween(hm(17), hm(17, 2), firstAlerts(HIGH, eight, carOn8, 3, "car"), "8 min HIGH")
        assertAlertsBetween(hm(17), hm(17, 2), firstAlerts(MEDIUM, eight, carOn8, 3, "car"), "8 min MEDIUM")
        assertNever(firstAlerts(LOW, eight, carOn8, 3, "car"), "8 min LOW")

        val six = commute(6, days = 3)
        val carOn6 = listOf(carried("car", -65, whileDriving(six)))
        assertAlertsBetween(hm(17), hm(17, 2), firstAlerts(HIGH, six, carOn6, 3, "car"), "6 min HIGH")
        assertNever(firstAlerts(MEDIUM, six, carOn6, 3, "car"), "6 min MEDIUM")
        assertNever(firstAlerts(LOW, six, carOn6, 3, "car"), "6 min LOW")
    }

    @Test fun aFourMinuteHopIsMissedAtEverySetting() {
        // Known miss. Two or three sweeps per trip do not cover three places.
        val legs = commute(4, days = 3)
        val car = listOf(carried("car", -65, whileDriving(legs)))
        for (s in Sensitivity.entries) assertNever(firstAlerts(s, legs, car, 3, "car"), "4 min $s")
    }

    @Test fun aFaintTrackerNeedsAHigherSensitivity() {
        // Under the chassis rather than in the cabin.
        val legs = commute(20, days = 3)
        val faint = listOf(carried("car", -82, whileDriving(legs)))
        assertAlertsBetween(hm(17), hm(17, 2), firstAlerts(HIGH, legs, faint, 3, "car"), "-82 HIGH")
        assertNever(firstAlerts(MEDIUM, legs, faint, 3, "car"), "-82 MEDIUM")
        assertNever(firstAlerts(LOW, legs, faint, 3, "car"), "-82 LOW")
        // Known miss: too faint to count at any setting.
        val fainter = listOf(carried("car", -88, whileDriving(legs)))
        for (s in Sensitivity.entries) assertNever(firstAlerts(s, legs, fainter, 3, "car"), "-88 $s")
    }

    // --- something slipped into a bag -------------------------------------

    @Test fun somethingInABagIsFlaggedAfterOneToThreeHours() {
        // With the user from 08:00: a half-hour drive, then a morning at work.
        val legs = commute(30, days = 1)
        val bag = listOf(carried("bag", -60) { it >= hm(8) })
        assertAlertsBetween(hm(9), hm(9, 4), firstAlerts(HIGH, legs, bag, 1, "bag"), "HIGH")
        assertAlertsBetween(hm(10), hm(10, 4), firstAlerts(MEDIUM, legs, bag, 1, "bag"), "MEDIUM")
        assertAlertsBetween(hm(11), hm(11, 4), firstAlerts(LOW, legs, bag, 1, "bag"), "LOW")
    }

    @Test fun plantedAtADeskItIsFlaggedOnceTheUserTravels() {
        // In the bag from noon, while the user sits at work. One place is one
        // place however long it lasts; the drive home is what gives it away.
        val legs = commute(30, days = 1)
        val bag = listOf(carried("bag", -60) { it >= hm(12) })
        for (s in Sensitivity.entries) {
            assertAlertsBetween(hm(17, 2), hm(17, 10), firstAlerts(s, legs, bag, 1, "bag"), "$s")
        }
    }

    @Test fun aCrowdDoesNotHideIt() {
        // 300 other devices on the same drive, each a fresh address every sweep.
        val legs = commute(30, days = 1)
        val crowd = (0 until 300).map { n -> Source { t, _ -> "crowd-$n-$t" to -70 } }
        val bag = carried("bag", -60) { it >= hm(8) }
        assertAlertsBetween(hm(9), hm(9, 4), firstAlerts(HIGH, legs, crowd + bag, 1, "bag"), "HIGH in a crowd")
    }

    // --- false alarms the rule cannot avoid -------------------------------

    @Test fun oneSharedRideIsFlaggedOnlyWhenItIsLong() {
        // A stranger's device a few seats away for the whole ride.
        fun ride(minutes: Long): Pair<List<Leg>, List<Source>> {
            val legs = listOf(Leg(hm(8), hm(8) + minutes, home, at(minutes * 1.0)))
            return legs to listOf(carried("seat", -70, whileDriving(legs)))
        }
        ride(50).let { (legs, s) -> for (x in Sensitivity.entries) assertNever(firstAlerts(x, legs, s, 1, "seat"), "50 min $x") }
        ride(70).let { (legs, s) ->
            assertAlertsBetween(hm(9), hm(9, 2), firstAlerts(HIGH, legs, s, 1, "seat"), "70 min HIGH")
            assertNever(firstAlerts(MEDIUM, legs, s, 1, "seat"), "70 min MEDIUM")
            assertNever(firstAlerts(LOW, legs, s, 1, "seat"), "70 min LOW")
        }
        ride(130).let { (legs, s) ->
            assertAlertsBetween(hm(10), hm(10, 2), firstAlerts(MEDIUM, legs, s, 1, "seat"), "130 min MEDIUM")
            assertNever(firstAlerts(LOW, legs, s, 1, "seat"), "130 min LOW")
        }
        ride(190).let { (legs, s) ->
            assertAlertsBetween(hm(11), hm(11, 2), firstAlerts(LOW, legs, s, 1, "seat"), "190 min LOW")
        }
    }

    @Test fun theSameStrangerBothWaysLooksExactlyLikeATrackerInTheCar() {
        // Known false alarm. A regular on the same train, close by on the way in and
        // on the way home, with a device that keeps one address. Nothing in what
        // the phone hears separates this from aTrackerInTheCarIsFlaggedOnTheDriveHome.
        val legs = listOf(Leg(hm(8), hm(8, 25), home, at(25.0)), Leg(hm(17), hm(17, 25), at(25.0), home))
        val stranger = listOf(carried("regular", -74, whileDriving(legs)))
        for (s in Sensitivity.entries) {
            assertAlertsBetween(hm(17), hm(17, 2), firstAlerts(s, legs, stranger, 1, "regular"), "$s")
        }
    }

    @Test fun aColleagueIsFlaggedOnlyOnHigh() {
        // Beside the user all day at work, and along to lunch and for a coffee.
        val work = at(10.0); val lunch = at(10.0, 0.6); val cafe = at(10.5, 0.3)
        val legs = listOf(
            Leg(hm(8), hm(8, 30), home, work),
            Leg(hm(12), hm(12, 8), work, lunch), Leg(hm(12, 50), hm(12, 58), lunch, work),
            Leg(hm(15), hm(15, 6), work, cafe), Leg(hm(15, 25), hm(15, 31), cafe, work),
            Leg(hm(17), hm(17, 30), work, home)
        )
        val colleague = listOf(carried("colleague", -68) { it >= hm(8, 30) && it < hm(17) })
        // Work, lunch, cafe: the third place is what tips it.
        assertAlertsBetween(hm(15), hm(15, 12), firstAlerts(HIGH, legs, colleague, 1, "colleague"), "HIGH")
        assertNever(firstAlerts(MEDIUM, legs, colleague, 1, "colleague"), "MEDIUM")
        assertNever(firstAlerts(LOW, legs, colleague, 1, "colleague"), "LOW")
    }

    // --- things that must stay quiet --------------------------------------

    /** Positions as a phone reports them indoors: off by tens of metres, now and
     *  then a wild fix 1.5 km away that claims to be good, now and then a coarse one. */
    private val shakyGps: (Long, Pos?) -> Pos? = { t, p ->
        p?.let {
            val k = (t / 2).toInt()
            when {
                k % 23 == 11 -> Pos(it.lat + 0.0135, it.lon, 20f)
                k % 17 == 5 -> Pos(it.lat - 0.004, it.lon + 0.005, 800f)
                else -> Pos(it.lat + ((k * 7) % 11 - 5) * 0.0001, it.lon + ((k * 5) % 9 - 4) * 0.00012, 25f)
            }
        }
    }

    @Test fun aNeighboursTelevisionNeverAlerts() {
        // Close and loud whenever the user is home, three days running.
        val legs = commute(30, days = 3)
        val tv = listOf(fixed("tv", home, -72))
        for (s in Sensitivity.entries) (0L..1L).forEach { phase ->
            val o = NearbyReplay.run(route(home, legs), tv, 3 * day, s, phase, shakyGps)
            assertEquals("$s", 0, o.alerts)
            assertEquals("$s", RiskState.OBSERVED, o.peak["ble:tv"])
        }
    }

    @Test fun aBeaconPassedEveryDayNeverAlerts() {
        // A beacon at the petrol station where the user stops for six minutes on
        // the way in and on the way home, three days running.
        val stop = at(10.0); val work = at(20.0)
        val legs = daily(
            listOf(
                Leg(hm(8), hm(8, 15), home, stop), Leg(hm(8, 21), hm(8, 36), stop, work),
                Leg(hm(17), hm(17, 15), work, stop), Leg(hm(17, 21), hm(17, 36), stop, home)
            ), 3
        )
        val beacon = listOf(fixed("shop", stop, -60, rangeM = 60.0))
        for (s in Sensitivity.entries) (0L..1L).forEach { phase ->
            val o = NearbyReplay.run(route(home, legs), beacon, 3 * day, s, phase)
            assertEquals("$s", 0, o.alerts)
            assertEquals("$s", RiskState.OBSERVED, o.peak["ble:shop"])
        }
    }

    // --- what cannot be caught at all -------------------------------------

    @Test fun aDeviceThatKeepsChangingAddressIsNeverFollowed() {
        // Known miss, and the biggest one. Carried all day, loud, but under a new
        // address every 15 minutes, as phones, watches and most earbuds do. Each
        // address is a stranger that vanishes before it has been anywhere.
        val legs = commute(30, days = 1)
        for (s in Sensitivity.entries) (0L..1L).forEach { phase ->
            val o = NearbyReplay.run(route(home, legs), listOf(rotating("rpa", -55)), day, s, phase)
            assertEquals("$s", 0, o.alerts)
            assertTrue("$s", o.idsRecorded > 90)
            assertTrue("$s", o.peak.values.all { it == RiskState.OBSERVED })
        }
    }

    @Test fun withoutLocationNothingCanBeSaidToMove() {
        // Known miss. Same bag, same day, but the phone has no position.
        val bag = listOf(carried("bag", -60) { it >= hm(8) })
        for (s in Sensitivity.entries) (0L..1L).forEach { phase ->
            val o = NearbyReplay.run({ null }, bag, day, s, phase)
            assertEquals("$s", 0, o.alerts)
            assertEquals("$s", RiskState.OBSERVED, o.peak["ble:bag"])
        }
    }

    @Test fun oneAlertPerDeviceWhileItStaysWithTheUser() {
        val legs = commute(30, days = 2)
        val bag = listOf(carried("bag", -60) { it >= hm(8) })
        val o = NearbyReplay.run(route(home, legs), bag, 2 * day, HIGH)
        assertEquals(1, o.alerts)
    }
}
