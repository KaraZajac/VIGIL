package org.soulstone.vigil.data.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationAvailability
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Wraps [FusedLocationProviderClient] and exposes the latest fix as a [StateFlow].
 * Ported from OVERWATCH. BALANCED power (~100 m) is plenty for geohash-7 place
 * bucketing and keeps the always-on scan easy on the battery.
 */
class LocationProvider(private val context: Context) {

    companion object {
        private const val TAG = "LocationProvider"
        private const val INTERVAL_MS = 15_000L
        private const val MIN_INTERVAL_MS = 5_000L
    }

    private val client: FusedLocationProviderClient by lazy {
        LocationServices.getFusedLocationProviderClient(context)
    }

    private val _location = MutableStateFlow<Location?>(null)
    val location: StateFlow<Location?> = _location.asStateFlow()

    /**
     * Whether a usable fix is available right now. Without one VIGIL cannot count
     * places or movement, so neither detection path can alert — the UI has to say
     * so rather than show "all quiet". Seeded from the system location switch at
     * start, then driven by the fused provider's own availability signal (which
     * also covers "on, but no fix obtainable here").
     */
    private val _available = MutableStateFlow(true)
    val available: StateFlow<Boolean> = _available.asStateFlow()

    private val request: LocationRequest = LocationRequest.Builder(
        Priority.PRIORITY_BALANCED_POWER_ACCURACY,
        INTERVAL_MS
    )
        .setMinUpdateIntervalMillis(MIN_INTERVAL_MS)
        .setWaitForAccurateLocation(false)
        .build()

    private val callback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            if (!running) return
            _location.value = result.lastLocation ?: return
            _available.value = true
        }

        override fun onLocationAvailability(availability: LocationAvailability) {
            if (!running) return
            _available.value = availability.isLocationAvailable
        }
    }

    @Volatile private var running = false

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        if (running) return true
        if (!hasPermission()) {
            Log.w(TAG, "ACCESS_FINE_LOCATION not granted")
            _available.value = false
            return false
        }
        return try {
            running = true
            _available.value = systemLocationOn()
            client.requestLocationUpdates(request, callback, Looper.getMainLooper())
            client.lastLocation.addOnSuccessListener { last ->
                if (running && last != null && _location.value == null) _location.value = last
            }
            true
        } catch (e: SecurityException) {
            running = false
            _available.value = false
            Log.e(TAG, "SecurityException starting location updates", e)
            false
        }
    }

    fun stop() {
        if (!running) return
        client.removeLocationUpdates(callback)
        running = false
        _location.value = null
        _available.value = true
    }

    private fun systemLocationOn(): Boolean {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return true
        return LocationManagerCompat.isLocationEnabled(lm)
    }
}
