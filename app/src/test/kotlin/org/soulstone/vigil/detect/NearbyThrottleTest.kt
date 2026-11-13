package org.soulstone.vigil.detect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.soulstone.vigil.model.SeparatedState
import org.soulstone.vigil.model.TrackerEcosystem
import org.soulstone.vigil.model.TrackerObservation

/** Locks which of the devices a sweep heard get written down. Defaults: again
 *  after 5 min or 100 m, strongest 96 at or above -90 dBm. */
class NearbyThrottleTest {

    private val lat = 40.75
    private val lon = -73.98
    private val min = 60_000L

    private fun obs(n: Int, rssi: Int = -60) = TrackerObservation(
        stableId = "ble:00:00:00:00:%02X:%02X".format(n shr 8, n and 0xFF),
        ecosystem = TrackerEcosystem.OTHER_BLE,
        mac = "00:00:00:00:%02X:%02X".format(n shr 8, n and 0xFF),
        rssi = rssi,
        separated = SeparatedState.UNKNOWN,
        label = "d$n"
    )

    @Test fun aNewDeviceIsRecordedThenLeftAloneWhileNothingChanges() {
        val t = NearbyThrottle()
        assertEquals(1, t.select(listOf(obs(1)), lat, lon, 0).size)
        assertEquals(0, t.select(listOf(obs(1)), lat, lon, 2 * min).size)
        assertEquals(0, t.select(listOf(obs(1)), lat, lon, 4 * min).size)
        // Five minutes on it is recorded again, so "last seen" does not go stale.
        assertEquals(1, t.select(listOf(obs(1)), lat, lon, 5 * min).size)
        assertEquals(0, t.select(listOf(obs(1)), lat, lon, 7 * min).size)
    }

    @Test fun movingMakesItWorthRecordingAgain() {
        val t = NearbyThrottle()
        t.select(listOf(obs(1)), lat, lon, 0)
        // ~56 m north: still the same spot.
        assertEquals(0, t.select(listOf(obs(1)), lat + 0.0005, lon, 2 * min).size)
        // ~167 m north: the device came along.
        assertEquals(1, t.select(listOf(obs(1)), lat + 0.0015, lon, 2 * min).size)
        // The distance is measured from where it was last RECORDED.
        assertEquals(0, t.select(listOf(obs(1)), lat + 0.0020, lon, 3 * min).size)
    }

    @Test fun theFirstFixAfterRecordingBlindIsRecorded() {
        val t = NearbyThrottle()
        assertEquals(1, t.select(listOf(obs(1)), null, null, 0).size)
        assertEquals(0, t.select(listOf(obs(1)), null, null, 2 * min).size)
        assertEquals(1, t.select(listOf(obs(1)), lat, lon, 2 * min).size)
        // Losing the fix again is not movement.
        assertEquals(0, t.select(listOf(obs(1)), null, null, 4 * min).size)
    }

    @Test fun devicesTooFaintToMatterAreNeverRecorded() {
        val t = NearbyThrottle()
        val picked = t.select(listOf(obs(1, -90), obs(2, -91), obs(3, -100)), lat, lon, 0)
        assertEquals(listOf("d1"), picked.map { it.label })
    }

    @Test fun theStrongestWinWhenThereAreTooMany() {
        val t = NearbyThrottle(maxPerSweep = 3)
        val heard = listOf(obs(1, -80), obs(2, -40), obs(3, -70), obs(4, -50), obs(5, -60))
        assertEquals(listOf("d2", "d4", "d5"), t.select(heard, lat, lon, 0).map { it.label })
    }

    @Test fun aFloodOfFakeAddressesCannotHideACloseDevice() {
        // 500 invented addresses at a middling signal, new ones every sweep, and one
        // real device close by. The close one is recorded every time it is due.
        val t = NearbyThrottle()
        var recorded = 0
        for (sweep in 0 until 10) {
            val flood = (0 until 500).map { obs(1000 + sweep * 500 + it, -75) }
            val picked = t.select(flood + obs(1, -55), lat + sweep * 0.002, lon, sweep * 2 * min)
            assertTrue(picked.size <= NearbyThrottle.MAX_PER_SWEEP)
            if (picked.any { it.label == "d1" }) recorded++
        }
        assertEquals(10, recorded)
    }

    @Test fun aFloodThatIsStrongerDoesHideIt() {
        // The limit of taking the strongest first, pinned so nobody claims more:
        // over a sweepful of addresses that are all stronger than the real device
        // keep it out of the record for as long as they keep coming.
        val t = NearbyThrottle()
        var recorded = 0
        for (sweep in 0 until 10) {
            val flood = (0 until 500).map { obs(1000 + sweep * 500 + it, -40) }
            val picked = t.select(flood + obs(1, -55), lat + sweep * 0.002, lon, sweep * 2 * min)
            if (picked.any { it.label == "d1" }) recorded++
        }
        assertEquals(0, recorded)
    }

    @Test fun forgettingMakesADeviceDueAgain() {
        val t = NearbyThrottle()
        assertEquals(2, t.select(listOf(obs(1), obs(2)), lat, lon, 0).size)
        // The write of d1 failed, so it must not sit out the five minutes.
        t.forget(listOf(obs(1).stableId))
        assertEquals(listOf("d1"), t.select(listOf(obs(1), obs(2)), lat, lon, 2 * min).map { it.label })
        // Forgetting something never heard is harmless.
        t.forget(listOf("ble:nobody"))
        assertEquals(0, t.select(listOf(obs(1), obs(2)), lat, lon, 3 * min).size)
    }

    @Test fun memoryOfOldDevicesIsBounded() {
        val t = NearbyThrottle(maxEntries = 10, maxPerSweep = 100)
        t.select((1..10).map { obs(it) }, lat, lon, 0)
        // Ten more push the first ten out of memory...
        t.select((11..20).map { obs(it) }, lat, lon, 1 * min)
        // ...so one of them is treated as new again rather than throttled.
        assertEquals(1, t.select(listOf(obs(1)), lat, lon, 2 * min).size)
        // A device still in memory stays throttled.
        assertEquals(0, t.select(listOf(obs(20)), lat, lon, 2 * min).size)
    }

    @Test fun clearForgetsEverything() {
        val t = NearbyThrottle()
        t.select(listOf(obs(1)), lat, lon, 0)
        t.clear()
        assertEquals(1, t.select(listOf(obs(1)), lat, lon, 1 * min).size)
    }
}
