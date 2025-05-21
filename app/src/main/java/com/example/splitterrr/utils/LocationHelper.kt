package com.example.splitterrr.utils

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.android.gms.location.Granularity
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.LocationSettingsRequest
import com.google.android.gms.location.Priority


class LocationHelper(private val context: Context) {

    private lateinit var locationRequest: LocationRequest

    sealed class LatestLocationResult {
        data class Success(val lat: Double, val lng: Double, val accuracy: Float) : LatestLocationResult()
        data object PermissionDenied : LatestLocationResult()
        data object GPSEnabledRequired : LatestLocationResult()
        data object LocationUnavailable : LatestLocationResult()
    }

    private val fusedLocationClient = LocationServices.getFusedLocationProviderClient(context)

    // Use only one cached high-accuracy request
    private val highAccuracyRequest: LocationRequest by lazy {
        LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 0L).apply {
            setMaxUpdates(1)
            setGranularity(Granularity.GRANULARITY_FINE)
            setDurationMillis(10000L) // Timeout after 10s if no good location
        }.build()
    }

    private fun isGPSEnabled(): Boolean {
        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        return locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
    }

    fun getCurrentLocation(onResult: (LatestLocationResult) -> Unit) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            onResult(LatestLocationResult.PermissionDenied)
            return
        }

        if (!isGPSEnabled()) {
            onResult(LatestLocationResult.GPSEnabledRequired)
            return
        }

        try {
            // Step 1: Try using last known location (if recent)
            fusedLocationClient.lastLocation.addOnSuccessListener { location ->
                val isRecent = location != null && System.currentTimeMillis() - location.time < 10_000
                if (isRecent) {
                    onResult(LatestLocationResult.Success(location!!.latitude, location.longitude, location.accuracy))
                } else {
                    // Step 2: Fallback to real-time GPS
                    requestFreshLocation(onResult)
                }
            }.addOnFailureListener {
                // If last location fails, fall back directly
                requestFreshLocation(onResult)
            }

        } catch (e: Exception) {
            e.printStackTrace()
            onResult(LatestLocationResult.LocationUnavailable)
        }
    }

    @SuppressLint("MissingPermission")
    private fun requestFreshLocation(onResult: (LatestLocationResult) -> Unit) {
        val locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.lastLocation?.let {
                    onResult(LatestLocationResult.Success(it.latitude, it.longitude, it.accuracy))
                    Log.e("LocationHelper", "Real-time location available")
                } ?: run {
                    Log.e("LocationHelper", "Real-time location unavailable")
                    onResult(LatestLocationResult.LocationUnavailable)
                }
                fusedLocationClient.removeLocationUpdates(this)
            }
        }

        fusedLocationClient.requestLocationUpdates(highAccuracyRequest, locationCallback, null).addOnSuccessListener {

        }
    }
}

