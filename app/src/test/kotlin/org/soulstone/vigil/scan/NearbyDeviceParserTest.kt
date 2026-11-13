package org.soulstone.vigil.scan

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.soulstone.vigil.model.AddressKind
import org.soulstone.vigil.model.SeparatedState
import org.soulstone.vigil.model.TrackerEcosystem

/** Locks how an arbitrary advert becomes a row in "Other devices nearby": what it
 *  is keyed on, what it is called, and what the app says about its address. */
class NearbyDeviceParserTest {

    @Before fun table() = BluetoothCompanyIds.setTableForTest(
        mapOf(0x004C to "Apple", 0x012D to "Sony", 0x0075 to "Samsung Electronics")
    )

    @After fun clear() = BluetoothCompanyIds.setTableForTest(null)

    private fun build(
        mac: String = "E4:9F:52:09:4A:9B",
        rssi: Int = -60,
        type: Int? = 1,
        name: String? = null,
        company: Int? = null,
        firstByte: Int? = null,
        services: List<Int> = emptyList()
    ) = NearbyDeviceParser.build(mac, rssi, type, name, company, firstByte, services, timestampMs = 1_000L)

    // --- identity -----------------------------------------------------------

    @Test fun identityIsTheAddressAndNothingElse() {
        val a = build(mac = "e4:9f:52:09:4a:9b", name = "WH-1000XM5", company = 0x012D)!!
        assertEquals("ble:E4:9F:52:09:4A:9B", a.stableId)
        assertEquals("E4:9F:52:09:4A:9B", a.mac)
        assertEquals(TrackerEcosystem.OTHER_BLE, a.ecosystem)
        assertTrue(a.stableId.startsWith(TrackerEcosystem.GENERIC_ID_PREFIX))
        // Never claims a separated state: there is no such flag on an arbitrary device.
        assertEquals(SeparatedState.UNKNOWN, a.separated)
        assertEquals(1_000L, a.timestampMs)
    }

    @Test fun twoDevicesWithTheSameNameStayTwoDevices() {
        // Every pair of the same headphones advertises the same name. Merging on it
        // would turn a carriage of strangers' earbuds into one device that follows you.
        val a = build(mac = "E4:9F:52:09:4A:9B", name = "WH-1000XM5")!!
        val b = build(mac = "E4:9F:52:09:4A:9C", name = "WH-1000XM5")!!
        assertTrue(a.stableId != b.stableId)
    }

    @Test fun malformedAddressIsRejected() {
        assertNull(build(mac = ""))
        assertNull(build(mac = "E4:9F:52:09:4A"))
        assertNull(build(mac = "E4-9F-52-09-4A-9B"))
        assertNull(build(mac = "G4:9F:52:09:4A:9B"))
        assertNull(build(mac = "E4:9F:52:09:4A:9B:00"))
        // Unicode digits are digits to Char.isDigit() but not to a Bluetooth address.
        assertNull(build(mac = "\u0664\u0664:9F:52:09:4A:9B"))
    }

    // --- names --------------------------------------------------------------

    @Test fun nameIsCleanedBeforeItReachesTheScreen() {
        assertEquals("", NearbyDeviceParser.cleanName(null))
        assertEquals("", NearbyDeviceParser.cleanName(" \t\n "))
        assertEquals("Evil Name", NearbyDeviceParser.cleanName("Evil\u0000\n\r\tName"))
        assertEquals("a b", NearbyDeviceParser.cleanName("  a   b  "))
    }

    @Test fun textDirectionTricksAreStripped() {
        // U+202E flips the reading order of what follows; U+200B is invisible. Both
        // could make a planted device read as something it is not.
        // Each becomes a space, so nothing invisible is left to hide in.
        assertEquals("My Air Pods", NearbyDeviceParser.cleanName("My\u202E Air\u200BPods"))
        assertTrue(NearbyDeviceParser.cleanName("\u202Eabc").none { Character.getType(it) == Character.FORMAT.toInt() })
    }

    @Test fun charactersBeyondTheBasicPlaneAreJudgedWhole() {
        // U+E0041 and U+E0042 are invisible "tag" characters, U+F0000 is private
        // use, U+D800 alone is half of nothing. Checked a UTF-16 unit at a time
        // each of these read as "surrogate" and passed.
        assertEquals("Bu ds", NearbyDeviceParser.cleanName("Bu\uDB40\uDC41\uDB40\uDC42ds"))
        assertEquals("Buds", NearbyDeviceParser.cleanName("Buds\uDB80\uDC00"))
        assertEquals("a b", NearbyDeviceParser.cleanName("a\uD800b"))
        assertEquals("a b", NearbyDeviceParser.cleanName("a\uDC00b"))
        // An emoji (U+1F3A7, headphones) is a real character and stays.
        assertEquals("\uD83C\uDFA7 Buds", NearbyDeviceParser.cleanName("\uD83C\uDFA7 Buds"))
        // Whatever goes in, no half character comes out.
        val out = NearbyDeviceParser.cleanName("x\uDBFF\uD83C\uDFA7\uDC00y")
        var i = 0
        while (i < out.length) {
            val cp = out.codePointAt(i)
            assertTrue(Character.getType(cp) != Character.SURROGATE.toInt())
            i += Character.charCount(cp)
        }
    }

    @Test fun stackedAccentsAreLimited() {
        // Ten combining acute accents on one letter draw over the lines around it.
        val tower = "a" + "\u0301".repeat(10) + "b"
        assertEquals("a" + "\u0301".repeat(NearbyDeviceParser.MAX_STACKED_MARKS) + "b",
            NearbyDeviceParser.cleanName(tower))
        // Two on a letter is ordinary writing (Vietnamese \u1EBF spelled out).
        assertEquals("e\u0302\u0301", NearbyDeviceParser.cleanName("e\u0302\u0301"))
    }

    @Test fun nameIsCappedWithoutSplittingACharacter() {
        val long = "x".repeat(200)
        assertEquals(NearbyDeviceParser.MAX_NAME_CHARS, NearbyDeviceParser.cleanName(long).length)
        // 31 letters then an emoji (two UTF-16 units): the cut must not leave half of it.
        val edge = "y".repeat(NearbyDeviceParser.MAX_NAME_CHARS - 1) + "\uD83D\uDE00" + "tail"
        val out = NearbyDeviceParser.cleanName(edge)
        assertTrue(out.length <= NearbyDeviceParser.MAX_NAME_CHARS)
        assertTrue(!Character.isHighSurrogate(out.last()))
    }

    // --- what to call it ----------------------------------------------------

    @Test fun labelPrefersNameThenMakerThenWhatItLooksLike() {
        assertEquals("WH-1000XM5", NearbyDeviceParser.label("WH-1000XM5", "Sony"))
        assertEquals("Sony device", NearbyDeviceParser.label("", "Sony"))
        assertEquals("Fast Pair accessory (Sony)", NearbyDeviceParser.label("", "Sony", "Fast Pair accessory"))
        assertEquals("Eddystone beacon", NearbyDeviceParser.label("", "", "Eddystone beacon"))
        assertEquals(NearbyDeviceParser.UNNAMED, NearbyDeviceParser.label("", ""))
    }

    @Test fun makerComesFromTheCompanyId() {
        val o = build(company = 0x0075)!!
        assertEquals("Samsung Electronics", o.vendor)
        assertEquals("Samsung Electronics device", o.label)
        assertEquals("", o.name)
    }

    @Test fun unknownCompanyIdGivesNoMaker() {
        val o = build(company = 0xFFFE)!!
        assertEquals("", o.vendor)
        assertEquals(NearbyDeviceParser.UNNAMED, o.label)
    }

    @Test fun iBeaconIsNotCalledAnAppleDevice() {
        // The iBeacon frame carries Apple's company id whoever built the beacon.
        val o = build(company = 0x004C, firstByte = 0x02)!!
        assertEquals("", o.vendor)
        assertEquals("iBeacon", o.label)
        // Other Apple frames are Apple devices.
        assertEquals("Apple device", build(company = 0x004C, firstByte = 0x10)!!.label)
    }

    @Test fun serviceDataGivesAHintWhenThereIsNothingBetter() {
        assertEquals("Fast Pair accessory", build(services = listOf(0x1234, 0xFE2C))!!.label)
        assertEquals("Buds", build(name = "Buds", services = listOf(0xFE2C))!!.label)
    }

    // --- address kind -------------------------------------------------------

    @Test fun addressKindFollowsWhatAndroidReports() {
        fun kind(mac: String, type: Int?) = NearbyDeviceParser.addressKind(mac, type)
        assertEquals(AddressKind.PUBLIC, kind("08:00:00:00:00:00", 0))
        assertEquals(AddressKind.STATIC, kind("E4:9F:52:09:4A:9B", 1))     // top bits 11
        assertEquals(AddressKind.STATIC, kind("C0:00:00:00:00:01", 1))
        assertEquals(AddressKind.RESOLVABLE, kind("6A:B8:11:12:63:A4", 1)) // top bits 01
        assertEquals(AddressKind.RESOLVABLE, kind("40:00:00:00:00:01", 1))
        assertEquals(AddressKind.NON_RESOLVABLE, kind("39:4A:37:4A:34:14", 1)) // top bits 00
        // Top bits 10 are reserved: say nothing rather than guess.
        assertEquals(AddressKind.UNKNOWN, kind("80:00:00:00:00:01", 1))
    }

    @Test fun addressKindIsNeverGuessedFromTheBitsAlone() {
        // Before Android 15 the type is not exposed. A public address may have any
        // top bits, so reading them would call a fixed device "changing" or the reverse.
        assertEquals(AddressKind.UNKNOWN, NearbyDeviceParser.addressKind("E4:9F:52:09:4A:9B", null))
        assertEquals(AddressKind.UNKNOWN, NearbyDeviceParser.addressKind("6A:B8:11:12:63:A4", null))
        // Anonymous (0xFF) and unknown (0xFFFF) types too.
        assertEquals(AddressKind.UNKNOWN, NearbyDeviceParser.addressKind("6A:B8:11:12:63:A4", 0xFF))
        assertEquals(AddressKind.UNKNOWN, NearbyDeviceParser.addressKind("6A:B8:11:12:63:A4", 0xFFFF))
    }

    @Test fun onlyPrivateAddressesAreSaidToRotate() {
        assertTrue(AddressKind.RESOLVABLE.rotates)
        assertTrue(AddressKind.NON_RESOLVABLE.rotates)
        assertTrue(!AddressKind.PUBLIC.rotates && !AddressKind.STATIC.rotates && !AddressKind.UNKNOWN.rotates)
        for (k in AddressKind.entries) assertEquals(k, AddressKind.fromCode(k.code))
        assertEquals(AddressKind.UNKNOWN, AddressKind.fromCode(99))
        assertNotNull(build(type = null))
    }
}
