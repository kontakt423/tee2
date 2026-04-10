package com.kraeutertee.util

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import androidx.core.content.ContextCompat
import com.google.android.gms.location.*
import com.google.android.gms.tasks.CancellationTokenSource
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

object LocationHelper {

    fun harvestLatitudeOffset(latitude: Double): Int = when {
        latitude > 60.0 ->  2
        latitude > 54.0 ->  1
        latitude > 48.0 ->  0
        latitude > 42.0 -> -1
        else            -> -2
    }

    fun climateZoneName(latitude: Double): String = when {
        latitude > 60.0 -> "Skandinavisch (> 60°N)"
        latitude > 54.0 -> "Norddeutschland / Nordeuropa"
        latitude > 48.0 -> "Mitteleuropa (Baseline)"
        latitude > 42.0 -> "Süddeutschland / Österreich / Schweiz"
        else            -> "Mediterran"
    }

    fun harvestOffsetDescription(latitude: Double): String {
        val offset = harvestLatitudeOffset(latitude)
        return when {
            offset > 0 -> "Ernte ca. $offset Monat${if (offset > 1) "e" else ""} später als Mitteleuropa"
            offset < 0 -> "Ernte ca. ${-offset} Monat${if (-offset > 1) "e" else ""} früher als Mitteleuropa"
            else       -> "Mitteleuropäischer Erntezeitraum"
        }
    }

    fun hasLocationPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(
            context, android.Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(
            context, android.Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

    /**
     * Tries getLastLocation first; if null (no cached fix), falls back to
     * getCurrentLocation so the map always gets a real GPS position.
     */
    suspend fun getBestLocation(context: Context): Location? {
        if (!hasLocationPermission(context)) return null
        return getLastLocation(context) ?: getCurrentLocation(context)
    }

    @SuppressLint("MissingPermission")
    private suspend fun getLastLocation(context: Context): Location? =
        try {
            suspendCancellableCoroutine { cont ->
                val client = LocationServices.getFusedLocationProviderClient(context)
                client.lastLocation
                    .addOnSuccessListener { loc -> if (cont.isActive) cont.resume(loc) }
                    .addOnFailureListener { if (cont.isActive) cont.resume(null) }
                    .addOnCanceledListener  { if (cont.isActive) cont.resume(null) }
            }
        } catch (_: Exception) { null }

    @SuppressLint("MissingPermission")
    private suspend fun getCurrentLocation(context: Context): Location? =
        try {
            val cts = CancellationTokenSource()
            suspendCancellableCoroutine { cont ->
                val client = LocationServices.getFusedLocationProviderClient(context)
                client.getCurrentLocation(Priority.PRIORITY_BALANCED_POWER_ACCURACY, cts.token)
                    .addOnSuccessListener { loc -> if (cont.isActive) cont.resume(loc) }
                    .addOnFailureListener { if (cont.isActive) cont.resume(null) }
                cont.invokeOnCancellation { cts.cancel() }
            }
        } catch (_: Exception) { null }

    // Keep old name for backward compat
    suspend fun getLastLocation(context: Context, forceNew: Boolean = false): Location? =
        if (forceNew) getCurrentLocation(context) else getBestLocation(context)
}
