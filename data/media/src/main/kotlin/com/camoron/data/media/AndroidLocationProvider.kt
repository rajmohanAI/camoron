package com.camoron.data.media

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import android.os.SystemClock
import com.camoron.domain.model.GeoPoint
import com.camoron.domain.repository.LocationProvider
import dagger.hilt.android.qualifiers.ApplicationContext
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
