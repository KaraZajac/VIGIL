package org.soulstone.vigil.ring

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
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
import java.util.UUID
import kotlin.coroutines.resume

/**
 * User-initiated "make it ring" over BLE GATT — VIGIL's ONE active operation
 * (detection stays fully passive). Connects to a separated tracker and writes the
 * DULT play-sound command, then disconnects.
 *
 * The cross-vendor DULT path covers Google Find My Device network tags and DULT
 * partners (Chipolo/Pebblebee/eufy/Motorola). Apple AirTags use Apple's own sound
 * service and will report "no remote-ring service" until that per-ecosystem path is
 * added. The UUIDs/opcode below are from the DULT draft and are pending on-device
 * verification.
 */
object TrackerRinger {

    private const val TAG = "TrackerRinger"
    private const val TIMEOUT_MS = 15_000L

    // DULT accessory-protocol non-owner sound (IETF draft). VERIFY on-device.
    private val DULT_SERVICE = UUID.fromString("15190001-12F4-C226-88ED-2AC5579F2A85")
    private val DULT_NON_OWNER_CHAR = UUID.fromString("8E0C0001-1D68-FB92-BF61-48377421680E")
    private val DULT_SOUND_START = byteArrayOf(0x00, 0x03) // opcode 0x0300 (verify endianness/format)

    /** Returns a short user-facing status message. [ecosystem] is retained for the
     *  per-ecosystem paths (e.g. Apple) still to be added. */
    suspend fun ring(context: Context, mac: String, ecosystem: String): String {
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
            val callback = object : BluetoothGattCallback() {
                override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
                    when (newState) {
                        BluetoothProfile.STATE_CONNECTED -> g.discoverServices()
                        BluetoothProfile.STATE_DISCONNECTED -> {
                            if (cont.isActive) cont.resume("Tracker disconnected before it could ring.")
                            g.close()
                        }
                    }
                }

                override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
                    val ch = g.getService(DULT_SERVICE)?.getCharacteristic(DULT_NON_OWNER_CHAR)
                    if (ch == null) {
                        if (cont.isActive) cont.resume("This tracker type doesn't expose a remote-ring service.")
                        g.disconnect()
                        return
                    }
                    if (!writeSound(g, ch) && cont.isActive) {
                        cont.resume("Couldn't send the ring command.")
                        g.disconnect()
                    }
                }

                override fun onCharacteristicWrite(
                    g: BluetoothGatt,
                    ch: BluetoothGattCharacteristic,
                    status: Int
                ) {
                    if (cont.isActive) cont.resume(
                        if (status == BluetoothGatt.GATT_SUCCESS) "Ringing… listen for the tracker."
                        else "The tracker refused the ring command."
                    )
                    g.disconnect()
                }
            }
            gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
            cont.invokeOnCancellation { runCatching { gatt?.disconnect(); gatt?.close() } }
        }

    @Suppress("DEPRECATION")
    @SuppressLint("MissingPermission")
    private fun writeSound(g: BluetoothGatt, ch: BluetoothGattCharacteristic): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeCharacteristic(ch, DULT_SOUND_START, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) ==
                BluetoothGatt.GATT_SUCCESS
        } else {
            ch.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            ch.value = DULT_SOUND_START
            g.writeCharacteristic(ch)
        }

    private fun hasConnectPermission(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) ==
                PackageManager.PERMISSION_GRANTED
        } else true
}
