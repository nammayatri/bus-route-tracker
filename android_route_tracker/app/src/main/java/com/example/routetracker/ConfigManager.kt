package com.example.routetracker

import android.util.Log
import okhttp3.*
import org.json.JSONObject
import java.io.IOException

object ConfigManager {
    var locationUpdateInterval: Long = 3000L
    var stopDistanceRadiusThresholdMeters: Double = 100.0
    var sendLocationUpdates: Boolean = true
    var distanceThresholdMeters: Double = 10.0 // Set to a safe default

    fun fetchConfigs(city: String, vehicleType: String, onComplete: (() -> Unit)? = null) {
        val url = "${Constants.BASE_URL}/routeTrackerApi/configs/$city/$vehicleType"
        val request = Request.Builder().url(url).get().build()
        OkHttpClient().newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e("ConfigManager", "Failed to fetch configs: ${e.message}")
                onComplete?.invoke()
            }
            override fun onResponse(call: Call, response: Response) {
                if (response.isSuccessful) {
                    response.body?.string()?.let { body ->
                        try {
                            val json = JSONObject(body)
                            locationUpdateInterval = json.optLong("LOCATION_UPDATE_INTERVAL", locationUpdateInterval)
                            stopDistanceRadiusThresholdMeters = json.optDouble("STOP_DISTANCE_RADIUS_THRESHOLD_METERS", stopDistanceRadiusThresholdMeters)
                            sendLocationUpdates = json.optBoolean("SEND_LOCATION_UPDATES", sendLocationUpdates)
                            distanceThresholdMeters = json.optDouble("DISTANCE_THRESHOLD_METERS", distanceThresholdMeters)
                            Log.d("ConfigManager", "Fetched config: " +
                                "locationUpdateInterval=$locationUpdateInterval, " +
                                "stopDistanceRadiusThresholdMeters=$stopDistanceRadiusThresholdMeters, " +
                                "sendLocationUpdates=$sendLocationUpdates, " +
                                "distanceThresholdMeters=$distanceThresholdMeters"
                            )
                        } catch (e: Exception) {
                            Log.e("ConfigManager", "Error parsing configs: ${e.message}")
                        }
                    }
                } else {
                    Log.e("ConfigManager", "Config API error: ${response.code}")
                }
                onComplete?.invoke()
            }
        })
    }
}