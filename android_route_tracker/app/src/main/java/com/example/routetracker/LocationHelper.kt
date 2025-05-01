package com.example.routetracker

import android.app.Activity
import android.location.Location
import android.os.Handler
import android.os.Looper
import com.google.android.gms.location.*

object LocationHelper {
    fun fetchFreshOrLastLocation(activity: Activity, onResult: (Location?) -> Unit) {
        val fusedLocationClient = LocationServices.getFusedLocationProviderClient(activity)
        var usedLocation = false
        val locationRequest = LocationRequest.create().apply {
            priority = LocationRequest.PRIORITY_HIGH_ACCURACY
            numUpdates = 1
            interval = 0
        }
        val locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                if (!usedLocation) {
                    usedLocation = true
                    val freshLocation = result.lastLocation
                    if (freshLocation != null) {
                        onResult(freshLocation)
                    } else {
                        fusedLocationClient.lastLocation.addOnSuccessListener { lastLoc ->
                            onResult(lastLoc)
                        }
                    }
                    fusedLocationClient.removeLocationUpdates(this)
                }
            }
        }
        fusedLocationClient.requestLocationUpdates(locationRequest, locationCallback, null)
        Handler(Looper.getMainLooper()).postDelayed({
            if (!usedLocation) {
                usedLocation = true
                fusedLocationClient.removeLocationUpdates(locationCallback)
                fusedLocationClient.lastLocation.addOnSuccessListener { lastLoc ->
                    onResult(lastLoc)
                }
            }
        }, 3000)
    }
} 