package org.soulstone.vigil.scan

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.le.ScanResult
import android.os.Build
import androidx.annotation.RequiresApi
import org.soulstone.vigil.model.AddressKind
import org.soulstone.vigil.model.SeparatedState
import org.soulstone.vigil.model.TrackerEcosystem
import org.soulstone.vigil.model.TrackerObservation

/**
 * Turns any BLE advert that is NOT one of the tracker wire formats into a
 * [TrackerObservation] of the catch-all [TrackerEcosystem.OTHER_BLE] class:
 * headphones, watches, car kits, beacons, and trackers VIGIL has no parser for.
 *
 * Identity is the advertising address and nothing else. Two limits follow, and the
 * UI states both:
 *  - a device that re-rolls its address (phones and watches do, regularly, and so
 *    do some earbuds and other accessories) appears as a new device after each
 *    roll and can never build up a co-movement history;
 *  - names and maker ids are deliberately NOT used to merge rows: every pair of the
 *    same headphones advertises the same name, so merging on it would turn a train
 *    carriage of strangers' earbuds into one device that follows you everywhere.
 *
 * The pure helpers take plain values so they can be unit-tested off-device.
 */
object NearbyDeviceParser {

    const val MAX_NAME_CHARS = 32
    /** Combining accents kept on one letter. Vietnamese needs two; four is ample. */
    const val MAX_STACKED_MARKS = 4
    const val UNNAMED = "Unnamed device"

    // BluetoothDevice.ADDRESS_TYPE_* (public API values; inlined so the pure helper
    // needs no Android class).
    private const val TYPE_PUBLIC = 0
    private const val TYPE_RANDOM = 1

    private const val APPLE_COMPANY_ID = 0x004C
    private const val APPLE_TYPE_IBEACON = 0x02

    // A few 16-bit service-data UUIDs worth naming when there is nothing better.
    private val serviceHints = mapOf(
        0xFE2C to "Fast Pair accessory",
        0xFEAA to "Eddystone beacon",
        0xFD6F to "Exposure notification beacon"
    )

    @SuppressLint("MissingPermission")
    fun parse(result: ScanResult): TrackerObservation? {
        // Anything the tracker parser understands belongs to the tracker scan, which
        // is always running; listing it twice would double-count it.
        if (TrackerParser.parse(result) != null) return null
        val device = result.device ?: return null
        val mac = device.address ?: return null
        val record = result.scanRecord

        val apiType: Int? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            runCatching { Api35.addressType(device) }.getOrNull()
        } else null

        var companyId: Int? = null
        var firstMfgByte: Int? = null
        record?.manufacturerSpecificData?.let { m ->
            if (m.size() > 0) {
                companyId = m.keyAt(0)
                firstMfgByte = m.valueAt(0)?.firstOrNull()?.toInt()?.and(0xFF)
            }
        }
        val service16 = record?.serviceData?.keys?.mapNotNull { uuid16(it.uuid) } ?: emptyList()

        return build(
            mac = mac,
            rssi = result.rssi,
            apiAddressType = apiType,
            rawName = record?.deviceName,
            companyId = companyId,
            firstMfgByte = firstMfgByte,
            serviceData16 = service16
        )
    }

    /** Pure core of [parse]. */
    fun build(
        mac: String,
        rssi: Int,
        apiAddressType: Int?,
        rawName: String?,
        companyId: Int?,
        firstMfgByte: Int?,
        serviceData16: List<Int>,
        timestampMs: Long = System.currentTimeMillis()
    ): TrackerObservation? {
        if (!isMac(mac)) return null
        val name = cleanName(rawName)
        // An iBeacon frame carries Apple's company id whoever built the beacon.
        val isIBeacon = companyId == APPLE_COMPANY_ID && firstMfgByte == APPLE_TYPE_IBEACON
        val vendor = when {
            isIBeacon -> ""
            companyId != null -> BluetoothCompanyIds.name(companyId)
            else -> ""
        }
        val hint = when {
            isIBeacon -> "iBeacon"
            else -> serviceData16.firstNotNullOfOrNull { serviceHints[it] } ?: ""
        }
        return TrackerObservation(
            stableId = TrackerEcosystem.GENERIC_ID_PREFIX + mac.uppercase(),
            ecosystem = TrackerEcosystem.OTHER_BLE,
            mac = mac.uppercase(),
            rssi = rssi,
            separated = SeparatedState.UNKNOWN,
            label = label(name, vendor, hint),
            timestampMs = timestampMs,
            name = name,
            vendor = vendor,
            addressKind = addressKind(mac, apiAddressType)
        )
    }

    /** What to call a device: its own name, else its maker, else what it looks like. */
    fun label(name: String, vendor: String, hint: String = ""): String = when {
        name.isNotEmpty() -> name
        vendor.isNotEmpty() && hint.isNotEmpty() -> "$hint ($vendor)"
        vendor.isNotEmpty() -> "$vendor device"
        hint.isNotEmpty() -> hint
        else -> UNNAMED
    }

    /**
     * Advertised names are attacker-controlled text shown in the UI, in a
     * notification and in the evidence export: strip control and formatting
     * characters, collapse whitespace, limit stacked accents, and cap the length.
     *
     * Judged a whole character at a time, not a UTF-16 unit at a time. Each half
     * of a character beyond the basic plane only says "surrogate", so checked by
     * halves the invisible tag characters (U+E0000 block) and private-use ones
     * there passed, and so did a lone half.
     */
    fun cleanName(raw: String?): String {
        if (raw == null) return ""
        val sb = StringBuilder(raw.length)
        var lastSpace = true
        var marks = 0
        var i = 0
        while (i < raw.length) {
            val cp = raw.codePointAt(i)
            i += Character.charCount(cp)
            val type = Character.getType(cp).toByte()
            val drop = type == Character.CONTROL || type == Character.FORMAT ||
                type == Character.PRIVATE_USE || type == Character.UNASSIGNED ||
                type == Character.SURROGATE ||
                type == Character.LINE_SEPARATOR || type == Character.PARAGRAPH_SEPARATOR
            if (drop || Character.isWhitespace(cp) || Character.isSpaceChar(cp)) {
                if (!lastSpace) sb.append(' ')
                lastSpace = true
                marks = 0
                continue
            }
            // Accents stacked without end draw over the lines around the name.
            val mark = type == Character.NON_SPACING_MARK || type == Character.ENCLOSING_MARK
            if (mark) {
                if (marks >= MAX_STACKED_MARKS) continue
                marks++
            } else {
                marks = 0
            }
            sb.appendCodePoint(cp)
            lastSpace = false
        }
        var out = sb.toString().trim()
        if (out.length > MAX_NAME_CHARS) {
            var cut = MAX_NAME_CHARS
            // Don't split a surrogate pair.
            if (Character.isHighSurrogate(out[cut - 1])) cut--
            out = out.substring(0, cut).trim()
        }
        return out
    }

    /**
     * Address kind from what Android reports. Below API 35 the type is not exposed,
     * and guessing from the address bits would be wrong for public addresses (whose
     * top bits are arbitrary), so it stays UNKNOWN.
     */
    fun addressKind(mac: String, apiAddressType: Int?): AddressKind = when (apiAddressType) {
        TYPE_PUBLIC -> AddressKind.PUBLIC
        TYPE_RANDOM -> {
            val top = mac.substring(0, 2).toIntOrNull(16)?.shr(6)
            when (top) {
                0b11 -> AddressKind.STATIC
                0b01 -> AddressKind.RESOLVABLE
                0b00 -> AddressKind.NON_RESOLVABLE
                else -> AddressKind.UNKNOWN
            }
        }
        else -> AddressKind.UNKNOWN
    }

    private fun isMac(s: String): Boolean =
        s.length == 17 && s.indices.all { i ->
            if (i % 3 == 2) s[i] == ':' else s[i] in '0'..'9' || s[i] in 'A'..'F' || s[i] in 'a'..'f'
        }

    /** The 16-bit short form of a Bluetooth base UUID, or null for a 128-bit one. */
    private fun uuid16(u: java.util.UUID): Int? {
        val base = 0x0000_0000_0000_1000L
        if (u.leastSignificantBits != -0x7fffff7fa064cb05L) return null  // 0x800000805F9B34FB
        val msb = u.mostSignificantBits
        if (msb and 0xFFFF_0000_FFFF_FFFFuL.toLong() != base) return null
        return ((msb ushr 32) and 0xFFFF).toInt()
    }
}

/**
 * Kept in its own class so that on Android 14 and older, where this method does
 * not exist, only this never-loaded class fails verification rather than the
 * whole parser (which logged a hidden-API warning on every load and ran unverified).
 */
@RequiresApi(Build.VERSION_CODES.VANILLA_ICE_CREAM)
private object Api35 {
    fun addressType(device: BluetoothDevice): Int = device.addressType
}
