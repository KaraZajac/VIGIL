package org.soulstone.vigil.detect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.soulstone.vigil.data.db.SightingEntity
import org.soulstone.vigil.detect.Places.Fix
import org.soulstone.vigil.model.RiskState
import org.soulstone.vigil.model.Sensitivity

/**
 * Locks the rule for "any other Bluetooth device". HIGH = 3 places / 60 min /
 * 4 sightings / -85 dBm; MEDIUM = 4 / 120 / 5 / -80; LOW = 5 / 180 / 6 / -75.
 * A sighting counts once per 15 minutes, or sooner at a new place.
 * Places are ~1.1 km apart here (0.01 degrees of latitude) unless a test says so.
 */
class NearbyDeviceEvaluatorTest {

    private val high = NearbyDeviceEvaluator.thresholdsFor(Sensitivity.HIGH)
    private val medium = NearbyDeviceEvaluator.thresholdsFor(Sensitivity.MEDIUM)
    private val low = NearbyDeviceEvaluator.thresholdsFor(Sensitivity.LOW)

    private val lat0 = 40.75
    private val lon0 = -73.98

    /** A sighting [tsMin] minutes in, at stop number [place] (null = no fix). */
    private fun s(tsMin: Long, place: Int?, rssi: Int = -60, acc: Float? = 10f) = SightingEntity(
        trackerId = "ble:AA:BB:CC:DD:EE:FF",
        timestamp = tsMin * 60_000L,
        rssi = rssi,
        separated = false,
        lat = place?.let { lat0 + it * 0.01 },
        lon = place?.let { lon0 },
        accuracy = if (place == null) null else acc
    )

    private fun eval(list: List<SightingEntity>, t: NearbyDeviceEvaluator.Thresholds = high) =
        NearbyDeviceEvaluator.evaluate(list, t)

    // --- the rule -----------------------------------------------------------

    @Test fun emptyIsObserved() {
        val a = eval(emptyList())
        assertEquals(RiskState.OBSERVED, a.riskState)
        assertEquals(0, a.distinctPlaces)
        assertEquals(0, a.sightings)
    }

    @Test fun closeAcrossThreePlacesForAnHourAlertsOnHigh() {
        val list = listOf(s(0, 0), s(20, 1), s(40, 2), s(60, 2))
        val a = eval(list)
        assertEquals(RiskState.ALERTING, a.riskState)
        assertEquals(3, a.distinctPlaces)
        assertEquals(4, a.sightings)
        assertEquals(60L, a.spanMin)
    }

    @Test fun oneMinuteShortOfTheSpanOnlyWatches() {
        val list = listOf(s(0, 0), s(20, 1), s(40, 2), s(59, 2))
        assertEquals(RiskState.SUSPICIOUS, eval(list).riskState)
    }

    @Test fun twoPlacesNeverAlertHoweverLong() {
        // Home and office, all day: the user's own headphones before they are marked.
        val list = (0..40).map { s(it * 15L, if (it % 8 < 4) 0 else 1) }
        val a = eval(list)
        assertEquals(2, a.distinctPlaces)
        assertEquals(RiskState.SUSPICIOUS, a.riskState)
    }

    @Test fun sittingStillCountsOncePerQuarterHour() {
        // Fifty minutes beside a device at one spot: four counted sightings, not eleven.
        val list = (0..10).map { s(it * 5L, 0) }
        val a = eval(list)
        assertEquals(4, a.sightings)
        assertEquals(1, a.distinctPlaces)
        assertEquals(RiskState.OBSERVED, a.riskState)
    }

    @Test fun movingOnCountsWithoutWaitingForTheClock() {
        // A twelve-minute drive, a sighting every two minutes, each a kilometre on.
        // By the clock alone that is one sighting; it is six different places.
        val drive = (0..5).map { s(it * 2L, it) }
        val a = eval(drive)
        assertEquals(6, a.sightings)
        assertEquals(6, a.distinctPlaces)
        // ...but ten minutes is not an hour, so it is only being watched.
        assertEquals(RiskState.OBSERVED, a.riskState)
        assertEquals(RiskState.SUSPICIOUS, eval(drive + s(16, 8)).riskState)
    }

    @Test fun aSecondShortDriveHoursLaterAlerts() {
        // The tracker-in-a-car case: close for a short drive in the morning and
        // again on the way home. Neither drive is long enough to count twice by
        // the clock, which is why moving on has to count.
        val out = (0..4).map { s(it * 2L, it) }
        val back = (0..4).map { s(540 + it * 2L, 4 - it) }
        assertEquals(RiskState.OBSERVED, eval(out, high).riskState)
        assertEquals(RiskState.ALERTING, eval(out + back.take(1), high).riskState)
        assertEquals(RiskState.ALERTING, eval(out + back.take(1), medium).riskState)
        assertEquals(RiskState.ALERTING, eval(out + back.take(1), low).riskState)
    }

    @Test fun wobbleIsNotMovingOn() {
        // Positions 200 m apart are the same place, so the clock still rules.
        val list = (0..6).map { i -> s(i * 2L, 0).let { it.copy(lat = it.lat!! + (i % 2) * 0.0018) } }
        assertEquals(1, eval(list).sightings)
        // So are positions a kilometre apart that each claim only 600 m accuracy.
        val coarse = (0..6).map { s(it * 2L, it % 2, acc = 600f) }
        assertEquals(1, eval(coarse).sightings)
        // And a sighting with no fix cannot show a move.
        assertEquals(1, eval(listOf(s(0, 0), s(2, null), s(4, null))).sightings)
    }

    @Test fun aStrayFixAddsSightingsButNeverAPlace() {
        // One wild fix in an evening at home counts twice (there, and back), which
        // is harmless: the stray place is dropped and one place alerts nobody.
        val list = listOf(s(0, 0), s(5, 0), s(10, 7), s(15, 0), s(20, 0), s(65, 0))
        val a = eval(list)
        assertEquals(1, a.distinctPlaces)
        assertEquals(RiskState.OBSERVED, a.riskState)
    }

    @Test fun threePlacesInAnHourOnThreeSightingsOnlyWatches() {
        // Places and time are there; the fourth sighting is not.
        val list = listOf(s(0, 0), s(20, 1), s(60, 2))
        val a = eval(list)
        assertEquals(3, a.distinctPlaces)
        assertEquals(3, a.sightings)
        assertEquals(60L, a.spanMin)
        assertEquals(RiskState.SUSPICIOUS, a.riskState)
        assertEquals(RiskState.ALERTING, eval(list + s(75, 2)).riskState)
    }

    @Test fun mediumNeedsEachOfItsThree() {
        // 4 places / 120 min / 5 sightings. Each case is short of exactly one.
        val threePlaces = listOf(s(0, 0), s(30, 1), s(60, 2), s(90, 2), s(120, 2))
        eval(threePlaces, medium).let {
            assertEquals(3, it.distinctPlaces); assertEquals(5, it.sightings); assertEquals(120L, it.spanMin)
            assertEquals(RiskState.SUSPICIOUS, it.riskState)
        }
        val shortOfTime = listOf(s(0, 0), s(30, 1), s(60, 2), s(90, 3), s(119, 3))
        eval(shortOfTime, medium).let {
            assertEquals(4, it.distinctPlaces); assertEquals(5, it.sightings); assertEquals(119L, it.spanMin)
            assertEquals(RiskState.SUSPICIOUS, it.riskState)
        }
        val fourSightings = listOf(s(0, 0), s(40, 1), s(80, 2), s(120, 3))
        eval(fourSightings, medium).let {
            assertEquals(4, it.distinctPlaces); assertEquals(4, it.sightings); assertEquals(120L, it.spanMin)
            assertEquals(RiskState.SUSPICIOUS, it.riskState)
        }
        val all = listOf(s(0, 0), s(30, 1), s(60, 2), s(90, 3), s(120, 3))
        assertEquals(RiskState.ALERTING, eval(all, medium).riskState)
    }

    @Test fun lowNeedsEachOfItsThree() {
        // 5 places / 180 min / 6 sightings.
        val fourPlaces = listOf(s(0, 0), s(36, 1), s(72, 2), s(108, 3), s(144, 3), s(180, 3))
        eval(fourPlaces, low).let {
            assertEquals(4, it.distinctPlaces); assertEquals(6, it.sightings); assertEquals(180L, it.spanMin)
            assertEquals(RiskState.SUSPICIOUS, it.riskState)
        }
        val shortOfTime = listOf(s(0, 0), s(36, 1), s(72, 2), s(108, 3), s(144, 4), s(179, 4))
        eval(shortOfTime, low).let {
            assertEquals(5, it.distinctPlaces); assertEquals(6, it.sightings); assertEquals(179L, it.spanMin)
            assertEquals(RiskState.SUSPICIOUS, it.riskState)
        }
        val fiveSightings = listOf(s(0, 0), s(45, 1), s(90, 2), s(135, 3), s(180, 4))
        eval(fiveSightings, low).let {
            assertEquals(5, it.distinctPlaces); assertEquals(5, it.sightings); assertEquals(180L, it.spanMin)
            assertEquals(RiskState.SUSPICIOUS, it.riskState)
        }
        val all = listOf(s(0, 0), s(36, 1), s(72, 2), s(108, 3), s(144, 4), s(180, 4))
        assertEquals(RiskState.ALERTING, eval(all, low).riskState)
    }

    @Test fun aFlaggedStateOnlyEverSettlesDownwards() {
        val o = RiskState.OBSERVED; val w = RiskState.SUSPICIOUS; val al = RiskState.ALERTING
        fun settled(stored: RiskState, supported: RiskState) = NearbyDeviceEvaluator.settled(stored, supported)
        assertEquals(o, settled(al, o))
        assertEquals(w, settled(al, w))
        assertEquals(o, settled(w, o))
        assertEquals(al, settled(al, al))
        // Heard again without a sighting is never a reason to raise it.
        assertEquals(o, settled(o, al))
        assertEquals(w, settled(w, al))
        assertEquals(o, settled(o, w))
    }

    @Test fun evidenceThatHasAgedOutSupportsNothing() {
        // What the repository asks about a flagged device that is now only heard
        // faintly: the same question, of the sightings still inside the window.
        val list = listOf(s(0, 0), s(20, 1), s(40, 2), s(60, 2))
        assertEquals(RiskState.ALERTING, eval(list).riskState)
        val dayLater = (24 * 60 + 30) * 60_000L
        val inWindow = list.filter { it.timestamp >= dayLater - CoMovementEvaluator.WINDOW_MS }
        assertEquals(listOf(40L, 60L), inWindow.map { it.timestamp / 60_000 })
        assertEquals(RiskState.OBSERVED, eval(inWindow).riskState)
        // Faint sightings since then do not hold it up either.
        assertEquals(RiskState.OBSERVED, eval(inWindow + s(24 * 60 + 30, 5, rssi = -93)).riskState)
    }

    @Test fun mediumAndLowAskForMore() {
        val hourAtThree = listOf(s(0, 0), s(20, 1), s(40, 2), s(60, 2))
        assertEquals(RiskState.SUSPICIOUS, eval(hourAtThree, medium).riskState)

        val twoHoursAtFour = listOf(s(0, 0), s(30, 1), s(60, 2), s(90, 3), s(120, 3))
        assertEquals(RiskState.ALERTING, eval(twoHoursAtFour, medium).riskState)
        assertEquals(RiskState.SUSPICIOUS, eval(twoHoursAtFour, low).riskState)

        val threeHoursAtFive = listOf(s(0, 0), s(36, 1), s(72, 2), s(108, 3), s(144, 4), s(180, 4))
        assertEquals(RiskState.ALERTING, eval(threeHoursAtFive, low).riskState)
    }

    @Test fun watchingNeedsTwoPlacesAndAQuarterHour() {
        assertEquals(RiskState.OBSERVED, eval(listOf(s(0, 0), s(14, 1))).riskState)
        assertEquals(RiskState.SUSPICIOUS, eval(listOf(s(0, 0), s(15, 1))).riskState)
        assertEquals(RiskState.OBSERVED, eval(listOf(s(0, 0), s(15, 0))).riskState)
    }

    // --- closeness ----------------------------------------------------------

    @Test fun aDeviceThatWasNeverCloseIsIgnored() {
        // In range at every stop (a car in the same traffic), never near.
        val list = listOf(s(0, 0, -88), s(20, 1, -90), s(40, 2, -89), s(60, 3, -88))
        val a = eval(list)
        assertEquals(RiskState.OBSERVED, a.riskState)
        assertEquals(0, a.distinctPlaces)
        assertTrue(!a.closeEnough)
    }

    @Test fun onlyCloseSightingsCount() {
        // Close at two stops, merely in range at the two others.
        val list = listOf(s(0, 0, -60), s(20, 1, -90), s(40, 2, -90), s(60, 3, -60))
        val a = eval(list)
        assertEquals(2, a.distinctPlaces)
        assertEquals(RiskState.SUSPICIOUS, a.riskState)
    }

    @Test fun theFloorTightensWithSensitivity() {
        val at82 = listOf(s(0, 0, -82), s(30, 1, -82), s(60, 2, -82), s(90, 3, -82), s(120, 3, -82))
        assertEquals(RiskState.ALERTING, eval(at82, high).riskState)   // floor -85
        assertEquals(RiskState.OBSERVED, eval(at82, medium).riskState) // floor -80
        val at85 = at82.map { it.copy(rssi = -85) }
        assertEquals(RiskState.ALERTING, eval(at85, high).riskState)   // floor is inclusive
    }

    // --- what is a place ----------------------------------------------------

    @Test fun aStationaryDeviceIsOnePlaceDespiteWobble() {
        // A neighbour's television heard from the sofa for five hours while the
        // phone's position wanders: up to ~90 m north-south and ~70 m east-west of
        // the truth, so as much as 220 m between two fixes.
        val list = (0..20).map { i ->
            s(i * 15L, 0).let { it.copy(lat = it.lat!! + (i % 5 - 2) * 0.0004, lon = it.lon!! + (i % 3 - 1) * 0.0008) }
        }
        val a = eval(list)
        assertEquals(1, a.distinctPlaces)
        assertEquals(RiskState.OBSERVED, a.riskState)
    }

    @Test fun fixesThatCouldBeTheSameSpotAreOnePlace() {
        // 1.1 km apart on paper, but each fix only claims to be good to 600 m
        // (cell-tower positioning): they may be the same spot, so they are.
        val coarse = listOf(Fix(lat0, lon0, 600f), Fix(lat0 + 0.01, lon0, 600f))
        assertEquals(1, Places.distinct(coarse))
        val sharp = listOf(Fix(lat0, lon0, 20f), Fix(lat0 + 0.01, lon0, 20f))
        assertEquals(2, Places.distinct(sharp))
    }

    @Test fun aVagueFirstFixDoesNotSwallowTheExactOnesAfterIt() {
        // The phone wakes with a cell-tower fix good to 2 km, then gets exact fixes
        // as the journey goes on: three stops 1.1 km apart, two fixes at each.
        val vague = Fix(lat0 + 0.004, lon0, 2000f)
        val stops = (0..2).flatMap { i -> List(2) { Fix(lat0 + i * 0.01, lon0, 15f) } }
        assertEquals(3, Places.distinct(listOf(vague) + stops))
        // It makes no difference where in the journey the vague one falls.
        assertEquals(3, Places.distinct(stops.take(3) + vague + stops.drop(3)))
        assertEquals(3, Places.distinct(stops + vague))
        // And through the whole rule: vague first, then an hour across three stops.
        val list = listOf(s(0, 0, acc = 2000f), s(2, 0), s(20, 1), s(40, 2), s(62, 2))
        val a = eval(list)
        assertEquals(3, a.distinctPlaces)
        assertEquals(RiskState.ALERTING, a.riskState)
    }

    @Test fun vagueFixesAddNoPlaceOfTheirOwn() {
        // An evening at one spot. Now and then the position falls back to a cell
        // tower 1.1 km off, and says so (good to 1.5 km).
        val home = Fix(lat0, lon0, 15f)
        val tower = Fix(lat0 + 0.01, lon0, 1500f)
        fun n(vararg p: Fix) = Places.distinct(p.toList())
        assertEquals(1, n(home, home, tower, tower, home, home))
        assertEquals(1, n(tower, tower, home, home, tower, tower))
        // Two towers, neither exact: still nowhere to tell apart.
        val other = Fix(lat0 - 0.012, lon0, 1500f)
        assertEquals(1, n(tower, tower, other, other, tower, tower))
        // A vague fix far from every exact one is a place all the same.
        val away = Fix(lat0 + 0.05, lon0, 1500f)
        assertEquals(2, n(home, home, away, away))
    }

    @Test fun twoWrongFixesRunningReadAsATrip() {
        // The limit of telling a bad fix from a visit, pinned so nobody claims more.
        // A position that is far off while claiming to be exact, twice running, is
        // what a short trip out and back looks like.
        val a = Fix(lat0, lon0, 10f)
        val x = Fix(lat0 + 0.01, lon0, 10f)
        val y = Fix(lat0 + 0.02, lon0, 10f)
        fun n(vararg p: Fix) = Places.distinct(p.toList())
        assertEquals(2, n(a, a, x, x, a, a))
        assertEquals(3, n(a, a, x, y, a, a))
    }

    @Test fun aHopelessFixPlacesNothing() {
        val list = listOf(s(0, 0), s(20, 1, acc = 6000f), s(40, 2, acc = 6000f), s(60, 0))
        val a = eval(list)
        assertEquals(1, a.distinctPlaces)
        assertEquals(RiskState.OBSERVED, a.riskState)
    }

    @Test fun sightingsWithoutAFixCountForTimeNotPlace() {
        val list = listOf(s(0, 0), s(20, null), s(40, null), s(60, 1))
        val a = eval(list)
        assertEquals(2, a.distinctPlaces)
        assertEquals(4, a.sightings)
        assertEquals(RiskState.SUSPICIOUS, a.riskState)
    }

    @Test fun oneStrayFixIsNotAPlace() {
        val a = Fix(lat0, lon0, 10f)
        val b = Fix(lat0 + 0.01, lon0, 10f)
        val c = Fix(lat0 + 0.02, lon0, 10f)
        val d = Fix(lat0 + 0.03, lon0, 10f)
        fun n(vararg p: Fix) = Places.distinct(p.toList())
        assertEquals(0, n())
        assertEquals(1, n(a))
        assertEquals(2, n(a, b))          // too little to judge: both stand
        assertEquals(1, n(a, b, a))       // there and straight back: a bad fix
        assertEquals(1, n(a, a, b))       // newest one waits for confirmation
        assertEquals(2, n(a, a, b, b))    // ...and gets it
        assertEquals(1, n(b, a, a))
        assertEquals(4, n(a, b, c, d))    // a real journey: nothing dropped
        assertEquals(3, n(a, b, c))
        assertEquals(2, n(a, a, b, a, a, c, c))
    }

    @Test fun theSameWrongSpotAgainAndAgainIsStillNotAPlace() {
        // A phone's position can snap to one particular wrong spot repeatedly (a
        // mislocated Wi-Fi network, the next cell tower). Recurring is not dwelling.
        val a = Fix(lat0, lon0, 10f)
        val b = Fix(lat0 + 0.01, lon0, 10f)
        val c = Fix(lat0 + 0.02, lon0, 10f)
        fun n(vararg p: Fix) = Places.distinct(p.toList())
        assertEquals(1, n(a, b, a, b, a))
        assertEquals(1, n(a, a, b, a, a, a, b, a, c, a))
        // Staying for two positions makes it real, on any one of the visits.
        assertEquals(2, n(a, b, a, b, b, a))
        // So does being on the way somewhere else.
        assertEquals(3, n(a, b, c, a, b, c))
        assertEquals(3, n(a, b, a, b, c))
        // The cost, accepted: a device heard once per visit, out and straight back
        // each time, is credited with the hub only.
        assertEquals(1, n(a, b, a, c, a))
    }

    @Test fun strayFixesCannotManufactureAnAlert() {
        // Stationary device, phone at home all evening, with a wild fix every hour.
        val list = (0..16).map { i -> s(i * 15L, if (i % 4 == 2) 5 + i else 0) }
        val a = eval(list)
        assertEquals(1, a.distinctPlaces)
        assertEquals(RiskState.OBSERVED, a.riskState)
    }

    @Test fun distanceIsMetres() {
        assertEquals(1113.2, Places.metres(40.0, -74.0, 40.01, -74.0), 0.5)
        assertEquals(0.0, Places.metres(40.0, -74.0, 40.0, -74.0), 1e-9)
        // One hundredth of a degree of longitude shrinks with latitude.
        assertEquals(852.8, Places.metres(40.0, -74.0, 40.0, -73.99), 1.0)
        // Across the date line is a short hop, not a trip round the world.
        assertTrue(Places.metres(0.0, 179.999, 0.0, -179.999) < 300.0)
    }
}
