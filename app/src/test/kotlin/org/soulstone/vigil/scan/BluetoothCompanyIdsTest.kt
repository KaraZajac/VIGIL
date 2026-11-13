package org.soulstone.vigil.scan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The maker table is a bundled asset; this checks both the reader and the file. */
class BluetoothCompanyIdsTest {

    private fun parse(text: String) = BluetoothCompanyIds.parse(text.byteInputStream())

    @Test fun readsHexTabName() {
        val t = parse("# comment\n004C\tApple\n012D\tSony\n")
        assertEquals(mapOf(0x004C to "Apple", 0x012D to "Sony"), t)
    }

    @Test fun skipsWhatItCannotRead() {
        val t = parse(
            listOf(
                "", "#004C\tHidden", "4C\tShort id", "004C Apple with a space",
                "ZZZZ\tNot hex", "0001\t   ", "0002\tKept \t"
            ).joinToString("\n")
        )
        assertEquals(mapOf(0x0002 to "Kept"), t)
    }

    @Test fun lookupIsEmptyUntilLoadedAndMasksTo16Bits() {
        BluetoothCompanyIds.setTableForTest(null)
        assertEquals("", BluetoothCompanyIds.name(0x004C))
        BluetoothCompanyIds.setTableForTest(mapOf(0x004C to "Apple"))
        try {
            assertEquals("Apple", BluetoothCompanyIds.name(0x004C))
            assertEquals("Apple", BluetoothCompanyIds.name(0x1004C))
            assertEquals("", BluetoothCompanyIds.name(0x004D))
        } finally {
            BluetoothCompanyIds.setTableForTest(null)
        }
    }

    @Test fun bundledAssetIsCompleteAndSane() {
        // Unit tests run from the module directory.
        val asset = File("src/main/assets/${BluetoothCompanyIds.ASSET}")
        assertTrue("asset missing at ${asset.absolutePath}", asset.isFile)
        val t = asset.inputStream().use(BluetoothCompanyIds::parse)
        assertTrue("only ${t.size} makers", t.size > 3500)
        assertEquals("Apple", t[0x004C])
        assertEquals("Sony", t[0x012D])
        assertEquals("Samsung Electronics", t[0x0075])
        assertEquals("Microsoft", t[0x0006])
        // Names are shown in the UI next to attacker-chosen text; keep them plain.
        for ((id, name) in t) {
            assertTrue("0x%04X is blank".format(id), name.isNotBlank())
            assertTrue("0x%04X too long: $name".format(id), name.length <= 80)
            assertTrue("0x%04X has a control char".format(id), name.none { it.isISOControl() })
        }
    }
}
