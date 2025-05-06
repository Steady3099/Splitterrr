package com.example.splitterrr.utils

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority


class LocationHelper(private val context: Context) {

    sealed class LatestLocationResult {
        data class Success(val lat: Double, val lng: Double,val accuracy: Float) : LatestLocationResult()
        object PermissionDenied : LatestLocationResult()
        object GPSEnabledRequired : LatestLocationResult()
        object LocationUnavailable : LatestLocationResult()
    }

    private val fusedLocationClient = LocationServices.getFusedLocationProviderClient(context)

    private fun isGPSEnabled(): Boolean {
        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        return locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
    }


    fun getCurrentLocation(accuracy: Boolean,onResult: (LatestLocationResult) -> Unit) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            onResult(LatestLocationResult.PermissionDenied)
            return
        }

        if(!isGPSEnabled()){
            onResult(LatestLocationResult.GPSEnabledRequired)
            return
        }

        try{
            fusedLocationClient.lastLocation.addOnSuccessListener { _ ->
                val request = LocationRequest.Builder(
                    Priority.PRIORITY_HIGH_ACCURACY,  // Request for the highest accuracy
                    0L                    // Immediate location, no interval
                ).apply {
                    setWaitForAccurateLocation(accuracy)  // Wait for an accurate location
                    setMaxUpdates(1)                  // Only request a single update
                }.build()

                val locationCallback = object : LocationCallback() {
                    override fun onLocationResult(result: LocationResult) {
                        fusedLocationClient.removeLocationUpdates(this)  // Remove updates after one result
                        result.lastLocation?.let {
                            // Real-time location
                            onResult(LatestLocationResult.Success(it.latitude, it.longitude, it.accuracy))
                        } ?: run {
                            // If location is still null (shouldn't happen with high accuracy), handle fallback
                            Log.e("LocationHelper", "Real-time location unavailable")
                            onResult(LatestLocationResult.LocationUnavailable)
                        }
                    }
                }

                // Request location updates to get real-time location
                fusedLocationClient.requestLocationUpdates(
                    request,
                    locationCallback,
                    Looper.getMainLooper()
                )
            }
        }catch (e: Exception){
            e.printStackTrace()
            onResult(LatestLocationResult.LocationUnavailable)
        }

    }
}