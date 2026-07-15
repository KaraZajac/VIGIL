package org.soulstone.vigil.ring

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import org.soulstone.vigil.model.TrackerEcosystem
import java.util.UUID
import kotlin.coroutines.resume

/**
 * User-initiated "make it ring" over BLE GATT — VIGIL's ONE active operation
 * (detection stays fully passive). Connects to a separated tracker and writes the
 * play-sound command, then disconnects. No pairing/bonding is used (non-owner sound
 * needs none), matching AirGuard.
 *
 * Protocols (from AirGuard's AppleFindMy.kt + the IETF DULT draft, tried in order by
 * whichever service the tag exposes):
 *  - AirTag native: write a single 0xAF byte, no CCCD; the tag rings and self-disconnects.
 *  - DULT (Google FMDN + Chipolo/Pebblebee/eufy/Motorola): enable indications, write 0x0300.
 *  - Find My legacy (fd44): enable notifications, write [0x01,0x00,0x03].
 * Samsung SmartTag and Tile expose NO non-owner ring — the app tells the user to use
 * the vendor app instead.
 */
object TrackerRinger {

    private const val TAG = "TrackerRinger"
    private const val TIMEOUT_MS = 15_000L
    private const val STATUS_PEER_DISCONNECT = 19  // GATT_CONN_TERMINATE_PEER_USER — AirTag "done ringing"

    private data class Proto(
        val name: String,
        val service: UUID,
        val characteristic: UUID,
        val start: ByteArray,
        val cccd: Boolean,
        val indicate: Boolean
    )

    private val AIRTAG = Proto(
        "AirTag", uuid("7DFC9000-7D1C-4951-86AA-8D9728F8D66C"),
        uuid("7DFC9001-7D1C-4951-86AA-8D9728F8D66C"), byteArrayOf(0xAF.toByte()), cccd = false, indicate = false
    )
    private val DULT = Proto(
        "DULT", uuid("15190001-12F4-C226-88ED-2AC5579F2A85"),
        uuid("8E0C0001-1D68-FB92-BF61-48377421680E"), byteArrayOf(0x00, 0x03), cccd = true, indicate = true
    )
    private val FINDMY = Proto(
        "FindMy", uuid("0000FD44-0000-1000-8000-00805F9B34FB"),
        uuid("4F860003-943B-49EF-BED4-2F730304427A"), byteArrayOf(0x01, 0x00, 0x03), cccd = true, indicate = false
    )
    private val PRIORITY = listOf(AIRTAG, DULT, FINDMY)
    private val CCCD = uuid("00002902-0000-1000-8000-00805F9B34FB")

    suspend fun ring(context: Context, mac: String, ecosystem: String): String {
        when (runCatching { TrackerEcosystem.valueOf(ecosystem) }.getOrNull()) {
            TrackerEcosystem.SAMSUNG_SMARTTAG ->
                return "Samsung SmartTags can only be rung from the SmartThings app (owner-only)."
            TrackerEcosystem.TILE ->
                return "Tiles can only be rung from the Tile app (owner-only)."
            else -> {}
        }
        if (mac.isBlank()) return "No recent address for this tracker — keep watching and try again."
        if (!hasConnectPermission(context)) return "Grant Bluetooth (Connect) to ring trackers."
        val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
            ?: return "Bluetooth unavailable."
        if (!adapter.isEnabled) return "Turn on Bluetooth to ring trackers."
        val device = try {
            adapter.getRemoteDevice(mac)
        } catch (e: IllegalArgumentException) {
            return "Invalid device address."
        }
        return try {
            withTimeout(TIMEOUT_MS) { attemptRing(context, device) }
        } catch (e: TimeoutCancellationException) {
            "Couldn't reach the tracker — it may be out of range, or a silent/modified tag that ignores ring commands."
        } catch (e: Exception) {
            Log.w(TAG, "ring failed", e)
            "Ring failed: ${e.message}"
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun attemptRing(context: Context, device: BluetoothDevice): String =
        suspendCancellableCoroutine { cont ->
            var gatt: BluetoothGatt? = null
            var selected: Proto? = null
            fun done(msg: String, g: BluetoothGatt) {
                if (cont.isActive) cont.resume(msg)
                g.disconnect()
            }
            val callback = object : BluetoothGattCallback() {
                override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
                    when (newState) {
                        BluetoothProfile.STATE_CONNECTED -> g.discoverServices()
                        BluetoothProfile.STATE_DISCONNECTED -> {
                            // An AirTag self-disconnects (status 19) once it has started ringing.
                            if (cont.isActive) {
                                if (selected == AIRTAG && status == STATUS_PEER_DISCONNECT)
                                    cont.resume("Ringing… listen for the AirTag.")
                                else cont.resume("Tracker disconnected before it could ring.")
                            }
                            g.close()
                        }
                    }
                }

                override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
                    val proto = PRIORITY.firstOrNull { g.getService(it.service) != null }
                        ?: return done("This tracker type doesn't expose a remote-ring service.", g)
                    val ch = g.getService(proto.service)?.getCharacteristic(proto.characteristic)
                        ?: return done("Ring characteristic missing on this tracker.", g)
                    selected = proto
                    if (proto.cccd) {
                        g.setCharacteristicNotification(ch, true)
                        val desc = ch.getDescriptor(CCCD)
                        val v = if (proto.indicate) BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
                        else BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                        if (desc == null || !writeDescriptor(g, desc, v)) writeStart(g, ch, proto)
                    } else {
                        if (!writeStart(g, ch, proto)) done("Couldn't send the ring command.", g)
                    }
                }

                override fun onDescriptorWrite(g: BluetoothGatt, desc: BluetoothGattDescriptor, status: Int) {
                    val proto = selected ?: return
                    val ch = g.getService(proto.service)?.getCharacteristic(proto.characteristic)
                        ?: return done("Ring characteristic missing on this tracker.", g)
                    if (!writeStart(g, ch, proto)) done("Couldn't send the ring command.", g)
                }

                override fun onCharacteristicWrite(g: BluetoothGatt, ch: BluetoothGattCharacteristic, status: Int) {
                    // AirTag reports via self-disconnect; others confirm here.
                    if (selected == AIRTAG) {
                        done("Ringing… listen for the AirTag.", g)
                    } else {
                        done(
                            if (status == BluetoothGatt.GATT_SUCCESS) "Ringing… listen for the tracker."
                            else "The tracker refused the ring command.", g
                        )
                    }
                }
            }
            gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
            cont.invokeOnCancellation { runCatching { gatt?.disconnect(); gatt?.close() } }
        }

    @Suppress("DEPRECATION")
    @SuppressLint("MissingPermission")
    private fun writeStart(g: BluetoothGatt, ch: BluetoothGattCharacteristic, proto: Proto): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeCharacteristic(ch, proto.start, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) ==
                BluetoothGatt.GATT_SUCCESS
        } else {
            ch.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            ch.value = proto.start
            g.writeCharacteristic(ch)
        }

    @Suppress("DEPRECATION")
    @SuppressLint("MissingPermission")
    private fun writeDescriptor(g: BluetoothGatt, desc: BluetoothGattDescriptor, value: ByteArray): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeDescriptor(desc, value) == BluetoothGatt.GATT_SUCCESS
        } else {
            desc.value = value
            g.writeDescriptor(desc)
        }

    private fun hasConnectPermission(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) ==
                PackageManager.PERMISSION_GRANTED
        } else true

    private fun uuid(s: String): UUID = UUID.fromString(s)
}
