package com.camoron.data.media

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Address
import android.location.Geocoder
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Looper
import android.os.SystemClock
import com.camoron.core.common.DiagnosticLog
import com.camoron.domain.model.GeoPlace
import com.camoron.domain.model.GeoPoint
import com.camoron.domain.repository.LocationProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import javax.inject.Inject

/**
 * Follows the location through the platform's GPS and network providers while the camera is on
 * screen, so a fix is ready the moment the shutter is pressed.
 */
internal class AndroidLocationProvider @Inject constructor(
    @ApplicationContext private val context: Context,
) : LocationProvider {

    private val manager: LocationManager? = context.getSystemService(LocationManager::class.java)

    @Volatile
    private var latest: Location? = null
    private var listening = false
    private val listener = LocationListener { location -> if (isBetter(location)) latest = location }

    @SuppressLint("MissingPermission") // allowed() checks it
    override fun start() {
        val manager = manager ?: return
        if (listening || !allowed()) return
        val providers = PROVIDERS.filter { runCatching { manager.isProviderEnabled(it) }.getOrDefault(false) }
        for (provider in providers) {
            runCatching { manager.getLastKnownLocation(provider) }.getOrNull()?.let { if (isBetter(it)) latest = it }
            runCatching {
                manager.requestLocationUpdates(provider, INTERVAL_MS, MIN_DISTANCE_M, listener, Looper.getMainLooper())
            }
        }
        listening = true
    }

    override fun stop() {
        if (!listening) return
        runCatching { manager?.removeUpdates(listener) }
        listening = false
    }

    override fun current(): GeoPoint? {
        if (!allowed()) return null
        val location = latest ?: return null
        val ageNanos = SystemClock.elapsedRealtimeNanos() - location.elapsedRealtimeNanos
        if (ageNanos > MAX_AGE_NANOS) return null
        return GeoPoint(location.latitude, location.longitude, location.altitude.takeIf { location.hasAltitude() })
    }

    override fun approximate(): GeoPoint? {
        if (!allowed()) return null
        val location = latest ?: return null
        return GeoPoint(location.latitude, location.longitude, location.altitude.takeIf { location.hasAltitude() })
    }

    override suspend fun place(point: GeoPoint): GeoPlace? {
        if (!Geocoder.isPresent()) return null
        val geocoder = Geocoder(context, Locale.getDefault())
        val address = withTimeoutOrNull(PLACE_TIMEOUT_MS) {
            runCatching { lookUp(geocoder, point) }
                .onFailure { DiagnosticLog.record(TAG, "Couldn't look up the place name", it) }
                .getOrNull()
        } ?: return null
        val title = listOfNotNull(address.subLocality ?: address.locality, address.adminArea, address.countryName)
            .filter { it.isNotBlank() }
            .distinct()
            .joinToString(", ")
        val line = (0..address.maxAddressLineIndex).mapNotNull { address.getAddressLine(it) }.joinToString(", ")
        if (title.isBlank() && line.isBlank()) return null
        return GeoPlace(title.ifBlank { line }, line)
    }

    private suspend fun lookUp(geocoder: Geocoder, point: GeoPoint): Address? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            suspendCancellableCoroutine { cont ->
                geocoder.getFromLocation(point.latitude, point.longitude, 1, object : Geocoder.GeocodeListener {
                    override fun onGeocode(addresses: MutableList<Address>) {
                        if (cont.isActive) cont.resumeWith(Result.success(addresses.firstOrNull()))
                    }

                    override fun onError(errorMessage: String?) {
                        if (cont.isActive) cont.resumeWith(Result.success(null))
                    }
                })
            }
        } else {
            withContext(Dispatchers.IO) {
                @Suppress("DEPRECATION")
                geocoder.getFromLocation(point.latitude, point.longitude, 1)?.firstOrNull()
            }
        }

    /** A clearly newer fix, or one about as recent and no less accurate (GPS over network). */
    private fun isBetter(candidate: Location): Boolean {
        val old = latest ?: return true
        val newer = candidate.elapsedRealtimeNanos - old.elapsedRealtimeNanos
        return newer > STALE_NANOS || (newer >= 0 && candidate.accuracy <= old.accuracy * ACCURACY_SLACK)
    }

    private fun allowed(): Boolean = PERMISSIONS.any {
        context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
    }

    private companion object {
        const val TAG = "Location"
        /** The place name needs the network; without it the stamp keeps the coordinates. */
        const val PLACE_TIMEOUT_MS = 4_000L
        val PROVIDERS = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
        val PERMISSIONS = listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        const val INTERVAL_MS = 5_000L
        const val MIN_DISTANCE_M = 5f
        const val STALE_NANOS = 30_000_000_000L
        /** Photos are tagged only with a fix from the last few minutes, never from a past place. */
        const val MAX_AGE_NANOS = 5 * 60_000_000_000L
        const val ACCURACY_SLACK = 1.5f
    }
}
