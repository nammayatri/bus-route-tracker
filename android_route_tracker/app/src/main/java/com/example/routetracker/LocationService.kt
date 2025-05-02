package com.example.routetracker

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.os.Build
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import com.google.android.gms.location.*
import com.example.routetracker.NetworkHelper
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import org.json.JSONObject
import java.io.IOException

class LocationService : Service() {
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationCallback: LocationCallback
    private var updateInterval: Long = 3000L // Default to 3 seconds
    private var lastSentLocation: Location? = null

    override fun onCreate() {
        super.onCreate()
        try {
            fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
            createNotificationChannel()
            setupLocationCallback()
        } catch (e: Exception) {
            Log.e("LocationService", "Exception in onCreate: ", e)
            stopSelf()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val fineLocationGranted = ActivityCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        if (!fineLocationGranted) {
            Log.e("LocationService", "Permission not granted in onStartCommand")
            stopSelf()
            return START_NOT_STICKY
        }
        updateInterval = ConfigManager.locationUpdateInterval
        Log.d("LocationService", "Using config: updateInterval=$updateInterval, sendLocationUpdates=${ConfigManager.sendLocationUpdates}, distanceThresholdMeters=${ConfigManager.distanceThresholdMeters}")
        try {
            startForeground(1, createNotification())
        } catch (e: Exception) {
            Log.e("LocationService", "Exception in startForeground: ", e)
            stopSelf()
            return START_NOT_STICKY
        }
        startLocationUpdates()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                Constants.LOCATION_CHANNEL_ID,
                Constants.LOCATION_CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = Constants.LOCATION_CHANNEL_DESC
            }
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            notificationIntent,
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, Constants.LOCATION_CHANNEL_ID)
            .setContentTitle(Constants.LOCATION_CHANNEL_NAME)
            .setContentText("Tracking your location every "+ (ConfigManager.locationUpdateInterval / 1000) +" seconds")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun setupLocationCallback() {
        locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                locationResult.lastLocation?.let { location ->
                    Log.d("LocationService", "onLocationResult: lat=${location.latitude}, lon=${location.longitude}, time=${location.time}")
                    val shouldSend = shouldSendLocation(location)
                    Log.d("LocationService", "shouldSendLocation: $shouldSend, sendLocationUpdates=${ConfigManager.sendLocationUpdates}")
                    if (shouldSend && ConfigManager.sendLocationUpdates) {
                        sendLocationToServer(location)
                        lastSentLocation = Location(location) // Make a copy
                    }
                }
            }
        }
    }

    private fun shouldSendLocation(newLocation: Location): Boolean {
        val last = lastSentLocation
        if (last == null) {
            Log.d("LocationService", "shouldSendLocation: lastSentLocation is null, will send.")
            return true
        }
        val distance = newLocation.distanceTo(last)
        Log.d("LocationService", "shouldSendLocation: distanceToLast=$distance, threshold=${ConfigManager.distanceThresholdMeters}")
        return distance > ConfigManager.distanceThresholdMeters.toFloat()
    }

    private fun startLocationUpdates() {
        val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, updateInterval)
            .setIntervalMillis(updateInterval)
            .setMinUpdateIntervalMillis(updateInterval)
            .setMaxUpdateDelayMillis(updateInterval)
            .build()
        fusedLocationClient.requestLocationUpdates(
            locationRequest,
            locationCallback,
            Looper.getMainLooper()
        )
    }

    private fun sendLocationToServer(location: Location) {
        Log.d("LocationService", "sendLocationToServer: lat=${location.latitude}, lon=${location.longitude}, time=${location.time}")
        // Try to get route/stop info if available
        val sharedPrefs = getSharedPreferences("Auth", Context.MODE_PRIVATE)
        val routeId = sharedPrefs.getString("route_id", null)
        val stopId = sharedPrefs.getString("stop_id", null)
        val stopName = sharedPrefs.getString("stop_name", null)
        val timestamp = java.time.Instant.ofEpochMilli(location.time).toString() // ISO8601
        val json = JSONObject().apply {
            put("lat", location.latitude)
            put("lon", location.longitude)
            put("timestamp", timestamp)
            if (routeId != null) put("route_id", routeId)
            if (stopId != null) put("stop_id", stopId)
            if (stopName != null) put("stop_name", stopName)
        }
        val url = "${Constants.BASE_URL}/routeTrackerApi/location-update"
        val body = RequestBody.create("application/json".toMediaType(), json.toString())
        NetworkHelper.authenticatedRequest(
            url,
            "POST",
            Constants.SESSION_API_TOKEN,
            body,
            onSuccess = {},
            onError = { /* Optionally log error */ }
        )
    }

    override fun onDestroy() {
        super.onDestroy()
        fusedLocationClient.removeLocationUpdates(locationCallback)
    }
} 