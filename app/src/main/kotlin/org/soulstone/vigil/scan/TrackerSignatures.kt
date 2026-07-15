package org.soulstone.vigil.scan

import android.os.ParcelUuid
import java.util.UUID

/**
 * BLE wire-format constants for the tracker ecosystems, per the VIGIL research
 * brief (2026-07-14). These are the scan targets. Offsets/values marked below
 * are the ones flagged for empirical re-capture (SmartTag2, 2024+ Tile, current
 * AirTag firmware) before they should be trusted absolutely.
 */
object TrackerSignatures {

    // Apple Find My — manufacturer-specific data under company 0x004C.
    const val APPLE_COMPANY_ID = 0x004C
    const val APPLE_TYPE_FINDMY = 0x12          // Apple message type: offline finding
    const val APPLE_STATUS_MAINTAINED_BIT = 0x04 // status byte bit2 set => near owner

    // Samsung — company id (family-wide, not tag-unique; the FD5A service is the anchor).
    const val SAMSUNG_COMPANY_ID = 0x0075

    // Google Find My Device network frame types (first service-data byte under FEAA).
    const val FMDN_FRAME_NORMAL = 0x40
    const val FMDN_FRAME_SEPARATED = 0x41       // unwanted-tracking / separated mode

    // 16-bit service UUIDs, expanded to the full Bluetooth base UUID.
    val FMDN_UUID: ParcelUuid = uuid16(0xFEAA)      // Google FMDN (Eddystone svc UUID)
    val SAMSUNG_UUID: ParcelUuid = uuid16(0xFD5A)   // Galaxy SmartTag offline finding
    val TILE_ACTIVE_UUID: ParcelUuid = uuid16(0xFEED)
    val TILE_PREACT_UUID: ParcelUuid = uuid16(0xFEEC)
    val TILE_LEGACY_UUID: ParcelUuid = uuid16(0xFE84)
    val DULT_UUID: ParcelUuid = uuid16(0xFCB2)      // unified DULT (near-owner bit = byte14 LSB)

    /** Service UUIDs used to build ScanFilters (enables screen-off scanning). */
    val trackerServiceUuids: List<ParcelUuid> = listOf(
        FMDN_UUID, SAMSUNG_UUID, TILE_ACTIVE_UUID, TILE_PREACT_UUID, TILE_LEGACY_UUID, DULT_UUID
    )

    /** Expand a 16-bit assigned number to the 128-bit `0000xxxx-0000-1000-8000-00805f9b34fb`. */
    fun uuid16(v: Int): ParcelUuid =
        ParcelUuid(UUID.fromString(String.format("%08x-0000-1000-8000-00805f9b34fb", 0x0000FFFF and v)))
}
