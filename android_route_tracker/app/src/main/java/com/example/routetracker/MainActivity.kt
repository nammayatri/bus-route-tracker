package com.example.routetracker

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.widget.ArrayAdapter
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import com.google.android.material.button.MaterialButton
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import android.location.Location
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import android.widget.EditText
import android.app.AlertDialog
import android.widget.LinearLayout
import com.example.routetracker.Constants

data class StopDisplayItem(
    val stopName: String,
    val distanceMeters: Double,
    val isTop3: Boolean,
    val stop_id: String,
    val lat: Double,
    val lon: Double
)

class MainActivity : AppCompatActivity() {
    companion object {
        var isLocationServiceRunning = false
    }

    private lateinit var routeField: TextView
    private lateinit var recordButton: MaterialButton
    private lateinit var statusText: TextView
    private lateinit var logoutButton: MaterialButton
    private lateinit var currentLocationText: TextView
    private lateinit var stopField: TextView
    private lateinit var topStopsLayout: LinearLayout

    private var routes: List<JSONObject> = listOf()
    private var stops: List<JSONObject> = listOf()
    private var isTracking = false
    private var isOnline = true
    private var isLowBattery = false
    private var selectedRouteId: String? = null
    private var lastKnownLocation: Location? = null
    private var selectedStop: StopDisplayItem? = null
    private var lastSentLocation: Location? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        routeField = findViewById(R.id.routeField)
        recordButton = findViewById(R.id.recordButton)
        statusText = findViewById(R.id.statusText)
        logoutButton = findViewById(R.id.logoutButton)
        currentLocationText = findViewById(R.id.currentLocationText)
        stopField = findViewById(R.id.stopField)
        topStopsLayout = findViewById(R.id.topStopsLayout)

        setupUI()
        checkAndRequestPermissions()
        registerNetworkReceiver()
        checkBatteryStatus()
        startLocationUpdatesEvery3Sec()

        logoutButton.setOnClickListener {
            getSharedPreferences("Auth", MODE_PRIVATE).edit().clear().apply()
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
        }

        routeField.setOnClickListener { showRoutePickerDialog() }

        recordButton.setOnClickListener {
            // Fetch the latest location before proceeding
            val fusedLocationClient = com.google.android.gms.location.LocationServices.getFusedLocationProviderClient(this)
            fusedLocationClient.lastLocation.addOnSuccessListener { location ->
                if (location != null) {
                    lastKnownLocation = location
                    confirmAndRecordStop()
                } else {
                    Toast.makeText(this, "Could not fetch current location. Please try again.", Toast.LENGTH_SHORT).show()
                }
            }
        }

        stopField.setOnClickListener { showStopPickerDialog() }
    }

    private fun setupUI() {
        fetchRoutes()
    }

    private fun checkAndRequestPermissions() {
        val permissionsNeeded = mutableListOf(android.Manifest.permission.ACCESS_FINE_LOCATION)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            permissionsNeeded.add(android.Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        }
        if (android.os.Build.VERSION.SDK_INT >= 34) { // Android 14+
            permissionsNeeded.add(android.Manifest.permission.FOREGROUND_SERVICE_LOCATION)
        }
        val permissionsToRequest = permissionsNeeded.filter {
            ActivityCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (permissionsToRequest.isNotEmpty()) {
            if (ActivityCompat.shouldShowRequestPermissionRationale(this, android.Manifest.permission.ACCESS_FINE_LOCATION)) {
                // Show rationale dialog
                androidx.appcompat.app.AlertDialog.Builder(this)
                    .setTitle("Location Permission Needed")
                    .setMessage("This app needs location permission to track your location. Please grant the permission to continue.")
                    .setPositiveButton("OK") { _, _ ->
                        ActivityCompat.requestPermissions(this, permissionsToRequest.toTypedArray(), 1002)
                    }
                    .setNegativeButton("Cancel") { _, _ ->
                        // Optionally, guide the user to settings
                        val intent = Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                        intent.data = android.net.Uri.fromParts("package", packageName, null)
                        startActivity(intent)
                    }
                    .show()
            } else {
                ActivityCompat.requestPermissions(this, permissionsToRequest.toTypedArray(), 1002)
            }
        } else {
            startLocationTracking()
        }
    }

    private fun startLocationTracking() {
        val fineLocationGranted = ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val backgroundLocationGranted = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED
        } else true
        val foregroundServiceLocationGranted = if (android.os.Build.VERSION.SDK_INT >= 34) {
            ActivityCompat.checkSelfPermission(this, Manifest.permission.FOREGROUND_SERVICE_LOCATION) == PackageManager.PERMISSION_GRANTED
        } else true

        if (!fineLocationGranted || !backgroundLocationGranted || !foregroundServiceLocationGranted) {
            checkAndRequestPermissions()
            return
        }

        if (!isTracking) {
            try {
                val intent = Intent(this, LocationService::class.java).apply {
                    putExtra("update_interval", Constants.LOCATION_UPDATE_INTERVAL)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(intent)
                } else {
                    startService(intent)
                }
                isTracking = true
                updateUIForTrackingState()
                Toast.makeText(this, "Location tracking started", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Log.e("MainActivity", "Failed to start location tracking: ${e.message}")
                Toast.makeText(this, "Failed to start location tracking", Toast.LENGTH_SHORT).show()
                isTracking = false
                updateUIForTrackingState()
            }
        }
    }

    private fun stopLocationTracking() {
        if (isTracking) {
            try {
                stopService(Intent(this, LocationService::class.java))
                isTracking = false
                updateUIForTrackingState()
                Toast.makeText(this, "Location tracking stopped", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Log.e("MainActivity", "Failed to stop location tracking: ${e.message}")
                Toast.makeText(this, "Failed to stop location tracking", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun toggleLocationTracking() {
        if (isTracking) {
            stopLocationTracking()
        } else {
            startLocationTracking()
        }
    }

    private fun updateUIForTrackingState() {
        runOnUiThread {
            if (isTracking) {
                statusText.text = "Location tracking is active"
                recordButton.isEnabled = true
            } else {
                statusText.text = "Location tracking is stopped"
                recordButton.isEnabled = false
            }
        }
    }

    private fun getSessionCookie(): String? {
        return getSharedPreferences("Auth", MODE_PRIVATE).getString("session", null)
    }

    private fun fetchRoutes() {
        val sessionCookie = getSessionCookie()
        if (sessionCookie == null) {
            Toast.makeText(this, "Session expired. Please login again.", Toast.LENGTH_SHORT).show()
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
            return
        }

        val url = "${Constants.BASE_URL}/routeTrackerApi/routes"
        val request = Request.Builder()
            .url(url)
            .addHeader("Cookie", sessionCookie)
            .build()

        OkHttpClient().newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                runOnUiThread { 
                    Toast.makeText(this@MainActivity, "Failed to load routes: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
            override fun onResponse(call: Call, response: Response) {
                if (response.code == 401) {
                    runOnUiThread {
                        Toast.makeText(this@MainActivity, "Session expired. Please login again.", Toast.LENGTH_SHORT).show()
                        startActivity(Intent(this@MainActivity, LoginActivity::class.java))
                        finish()
                    }
                    return
                }
                if (response.isSuccessful) {
                    val responseBody = response.body
                    val arr = JSONArray(responseBody?.string())
                    routes = (0 until arr.length()).map { arr.getJSONObject(it) }
                    routes = routes.sortedWith(compareBy { it.optString("route_code", "") })
                    val routeNames = mutableListOf("Select Route...")
                    routeNames.addAll(routes.map {
                        val code = it.optString("route_code", "")
                        val start = it.optString("route_start_point", "")
                        val end = it.optString("route_end_point", "")
                        "$code | $start -> $end"
                    })
                    runOnUiThread {
                        val adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_dropdown_item_1line, routeNames)
                        routeField.text = routeNames[0]
                    }
                } else {
                    runOnUiThread {
                        Toast.makeText(this@MainActivity, "Failed to load routes: ${response.code}", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        })
    }

    private fun fetchStops(routeId: String) {
        val sessionCookie = getSessionCookie()
        if (sessionCookie == null) {
            Toast.makeText(this, "Session expired. Please login again.", Toast.LENGTH_SHORT).show()
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
            return
        }

        val url = "${Constants.BASE_URL}/routeTrackerApi/stops?route_id=$routeId"
        val request = Request.Builder()
            .url(url)
            .addHeader("Cookie", sessionCookie)
            .build()

        OkHttpClient().newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                runOnUiThread { 
                    Toast.makeText(this@MainActivity, "Failed to load stops: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
            override fun onResponse(call: Call, response: Response) {
                if (response.code == 401) {
                    runOnUiThread {
                        Toast.makeText(this@MainActivity, "Session expired. Please login again.", Toast.LENGTH_SHORT).show()
                        startActivity(Intent(this@MainActivity, LoginActivity::class.java))
                        finish()
                    }
                    return
                }
                if (response.isSuccessful) {
                    val responseBody = response.body
                    val arr = JSONArray(responseBody?.string())
                    stops = (0 until arr.length()).map { arr.getJSONObject(it) }
                    runOnUiThread {
                        updateStopListWithFilter("")
                    }
                } else {
                    runOnUiThread {
                        Toast.makeText(this@MainActivity, "Failed to load stops: ${response.code}", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        })
    }

    private fun updateStopListWithFilter(query: String) {
        val currentLocation = getCurrentLocation()
        val filtered = stops.filter {
            it.getString("stop_name").contains(query, ignoreCase = true)
        }
        val sorted = if (currentLocation != null) {
            filtered.sortedBy {
                val stopLat = it.optDouble("lat", 0.0)
                val stopLon = it.optDouble("lon", 0.0)
                val result = FloatArray(1)
                android.location.Location.distanceBetween(currentLocation.latitude, currentLocation.longitude, stopLat, stopLon, result)
                result[0].toDouble()
            }
        } else filtered
        val displayItems = sorted.mapIndexed { idx, stop ->
            val stopLat = stop.optDouble("lat", 0.0)
            val stopLon = stop.optDouble("lon", 0.0)
            val result = FloatArray(1)
            val dist = if (currentLocation != null) {
                android.location.Location.distanceBetween(currentLocation.latitude, currentLocation.longitude, stopLat, stopLon, result)
                result[0].toDouble()
            } else 0.0
            StopDisplayItem(
                stopName = stop.getString("stop_name"),
                distanceMeters = dist,
                isTop3 = idx < 3,
                stop_id = stop.optString("stop_id", ""),
                lat = stop.optDouble("lat", 0.0),
                lon = stop.optDouble("lon", 0.0)
            )
        }
        updateTopStopsChips(displayItems)
    }

    private fun updateTopStopsChips(stops: List<StopDisplayItem>) {
        topStopsLayout.removeAllViews()
        val nearest = stops.firstOrNull() ?: return
        val container = LinearLayout(this)
        container.orientation = LinearLayout.VERTICAL
        container.setPadding(0, 0, 0, 0)
        container.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )

        val stopNameView = TextView(this)
        stopNameView.text = nearest.stopName
        stopNameView.setTextColor(resources.getColor(R.color.primary, null))
        stopNameView.textSize = 15f
        stopNameView.setTypeface(null, android.graphics.Typeface.BOLD)
        stopNameView.setPadding(0, 0, 0, 4)
        stopNameView.gravity = android.view.Gravity.CENTER

        val distanceView = TextView(this)
        distanceView.text = String.format("%.2f km away", nearest.distanceMeters / 1000.0)
        distanceView.setTextColor(resources.getColor(R.color.black, null))
        distanceView.textSize = 14f
        distanceView.gravity = android.view.Gravity.CENTER

        container.setBackgroundResource(
            if (selectedStop?.stop_id == nearest.stop_id) R.drawable.selected_stop_background else R.drawable.unselected_stop_background
        )
        container.setOnClickListener {
            selectedStop = nearest
            stopField.text = nearest.stopName
            updateTopStopsChips(stops)
        }
        val params = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        params.setMargins(0, 0, 0, 16)
        container.layoutParams = params

        container.addView(stopNameView)
        container.addView(distanceView)
        topStopsLayout.addView(container)
    }

    private fun getCurrentLocation(): Location? {
        return lastKnownLocation
    }

    private fun showRoutePickerDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_route_picker, null)
        val searchEdit = dialogView.findViewById<EditText>(R.id.routeSearchEdit)
        val recyclerView = dialogView.findViewById<RecyclerView>(R.id.routeRecyclerView)
        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .create()

        val routeItems = routes
            .sortedWith(compareBy(
                { it.optString("route_code", "").toIntOrNull() ?: Int.MAX_VALUE },
                { it.optString("route_code", "") }
            ))
            .map {
                RouteDisplayItem(
                    routeCode = it.optString("route_code", ""),
                    routeStart = it.optString("route_start_point", ""),
                    routeEnd = it.optString("route_end_point", "")
                )
            }
        val adapter = RouteAdapter(routeItems) { selectedRoute ->
            routeField.text = "${selectedRoute.routeCode} | ${selectedRoute.routeStart} -> ${selectedRoute.routeEnd}"
            selectedRouteId = selectedRoute.routeCode
            getSharedPreferences("Auth", MODE_PRIVATE).edit().putString("route_id", selectedRoute.routeCode).apply()
            fetchStops(selectedRoute.routeCode)
            dialog.dismiss()
        }
        recyclerView.adapter = adapter
        recyclerView.layoutManager = LinearLayoutManager(this)

        searchEdit.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) {}
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val filtered = routeItems.filter {
                    it.routeCode.contains(s.toString(), true) ||
                    it.routeStart.contains(s.toString(), true) ||
                    it.routeEnd.contains(s.toString(), true)
                }
                adapter.updateRoutes(filtered)
            }
        })
        dialog.show()
    }

    private fun showStopPickerDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_stop_picker, null)
        val searchEdit = dialogView.findViewById<EditText>(R.id.stopSearchEdit)
        val recyclerView = dialogView.findViewById<RecyclerView>(R.id.stopRecyclerViewDialog)
        val dialogTopStopsLayout = dialogView.findViewById<LinearLayout>(R.id.dialogTopStopsLayout)
        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .create()

        val currentLocation = getCurrentLocation()
        val stopItems = stops.map { stop ->
            val stopLat = stop.optDouble("lat", 0.0)
            val stopLon = stop.optDouble("lon", 0.0)
            val result = FloatArray(1)
            val dist = if (currentLocation != null) {
                android.location.Location.distanceBetween(currentLocation.latitude, currentLocation.longitude, stopLat, stopLon, result)
                result[0].toDouble()
            } else 0.0
            StopDisplayItem(
                stopName = stop.optString("stop_name", ""),
                distanceMeters = dist,
                isTop3 = false, // We'll set this below for top 2
                stop_id = stop.optString("stop_id", ""),
                lat = stopLat,
                lon = stopLon
            )
        }.sortedBy { it.distanceMeters }
        // Mark top 2 as isTop3 = true
        val stopItemsWithTop2 = stopItems.mapIndexed { idx, item ->
            if (idx < 2) item.copy(isTop3 = true) else item
        }
        val adapter = StopAdapter(stopItemsWithTop2) { selected ->
            selectedStop = selected
            stopField.text = selected.stopName
            dialog.dismiss()
        }
        recyclerView.adapter = adapter
        recyclerView.layoutManager = LinearLayoutManager(this)

        
        searchEdit.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) {}
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val filtered = stopItemsWithTop2.filter {
                    it.stopName.contains(s.toString(), true)
                }
                adapter.updateStops(filtered)
            }
        })
        dialog.show()
    }

    private fun confirmAndRecordStop() {
        val manualStopEdit = findViewById<EditText>(R.id.manualStopEdit)
        val manualStopName = manualStopEdit?.text?.toString()?.trim()
        val stop = selectedStop
        val route = routeField.text.toString()
        val routeSelected = selectedRouteId != null && route != "Select Route"
        val stopProvided = (!manualStopName.isNullOrEmpty()) || (stop != null && stop.stopName.isNotEmpty())
        if (!routeSelected) {
            Toast.makeText(this, "Please select a route first", Toast.LENGTH_SHORT).show()
            return
        }
        if (!stopProvided) {
            Toast.makeText(this, "Please select a stop or enter a stop name", Toast.LENGTH_SHORT).show()
            return
        }
        val lat = lastKnownLocation?.latitude?.let { String.format("%.6f", it) } ?: "--"
        val lon = lastKnownLocation?.longitude?.let { String.format("%.6f", it) } ?: "--"
        val stopNameToShow = if (!manualStopName.isNullOrEmpty()) manualStopName else stop?.stopName ?: "-"
        // Show a beautiful custom Material dialog
        val dialogView = layoutInflater.inflate(R.layout.dialog_confirm_record, null)
        val stopText = dialogView.findViewById<TextView>(R.id.confirmStopText)
        val routeText = dialogView.findViewById<TextView>(R.id.confirmRouteText)
        val latText = dialogView.findViewById<TextView>(R.id.confirmLatText)
        val lonText = dialogView.findViewById<TextView>(R.id.confirmLonText)
        stopText.text = stopNameToShow
        routeText.text = route
        latText.text = "Lat: $lat"
        lonText.text = "Lon: $lon"
        val dialog = AlertDialog.Builder(this, R.style.MyMaterialAlertDialog)
            .setView(dialogView)
            .setPositiveButton("Yes") { _, _ -> recordStop() }
            .setNegativeButton("No", null)
            .create()
        dialog.show()
    }

    private fun recordStop() {
        val manualStopEdit = findViewById<EditText>(R.id.manualStopEdit)
        val manualStopName = manualStopEdit?.text?.toString()?.trim()
        val stop = selectedStop
        if ((stop == null || stop.stopName.isEmpty()) && manualStopName.isNullOrEmpty()) {
            Toast.makeText(this, "Please select a stop or enter a stop name", Toast.LENGTH_SHORT).show()
            return
        }
        if (!isTracking) {
            Toast.makeText(this, "Please start location tracking first", Toast.LENGTH_SHORT).show()
            return
        }
        if (selectedRouteId == null) {
            Toast.makeText(this, "Please select a route", Toast.LENGTH_SHORT).show()
            return
        }
        val route = routes.find { it.optString("route_code", "") == selectedRouteId }
        if (route == null) {
            Toast.makeText(this, "Invalid route selection", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val json = JSONObject().apply {
                put("route_id", route.getString("route_code"))
                if (!manualStopName.isNullOrEmpty()) {
                    put("stop_id", "manual")
                    put("stop_name", manualStopName)
                    put("lat", lastKnownLocation?.latitude ?: 0.0)
                    put("lon", lastKnownLocation?.longitude ?: 0.0)
                } else if (stop != null) {
                    put("stop_id", stop.stop_id)
                    put("stop_name", stop.stopName)
                    put("lat", stop.lat)
                    put("lon", stop.lon)
                }
            }
            Log.d("MainActivity", "Recording stop with payload: $json")
            val url = "${Constants.BASE_URL}/routeTrackerApi/record"
            val body = RequestBody.create("application/json".toMediaType(), json.toString())
            val sessionCookie = getSessionCookie()
            if (sessionCookie == null) {
                Toast.makeText(this, "Session expired. Please login again.", Toast.LENGTH_SHORT).show()
                startActivity(Intent(this, LoginActivity::class.java))
                finish()
                return
            }
            val request = Request.Builder()
                .url(url)
                .post(body)
                .addHeader("Cookie", sessionCookie)
                .build()
            OkHttpClient().newCall(request).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    runOnUiThread {
                        Toast.makeText(this@MainActivity, "Failed to record stop: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                }
                override fun onResponse(call: Call, response: Response) {
                    runOnUiThread {
                        if (response.isSuccessful) {
                            Toast.makeText(this@MainActivity, "Stop recorded successfully", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(this@MainActivity, "Failed to record stop: ${response.code}", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            })
        } catch (e: Exception) {
            Log.e("MainActivity", "Error recording stop: ${e.message}")
            Toast.makeText(this, "Error recording stop: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun sendLocationUpdate(latitude: Double, longitude: Double) {
        if (!isTracking) return

        val sessionCookie = getSessionCookie()
        if (sessionCookie == null) {
            Toast.makeText(this, "Session expired. Please login again.", Toast.LENGTH_SHORT).show()
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
            return
        }

        try {
            val routeName = routeField.text.toString()
            val stopName = routeField.text.toString()
            val routeIndex = routes.indexOfFirst { it.getString("route_name") == routeName }
            val stopIndex = stops.indexOfFirst { it.getString("stop_name") == stopName }
            
            val json = JSONObject().apply {
                put("lat", latitude)
                put("lon", longitude)
                put("timestamp", System.currentTimeMillis())
                if (routeIndex != -1 && stopIndex != -1) {
                    put("route_id", routes[routeIndex].getString("route_code"))
                    put("stop_id", stops[stopIndex].getString("stop_id"))
                    put("stop_name", stops[stopIndex].getString("stop_name"))
                }
            }
            
            val url = "${Constants.BASE_URL}/routeTrackerApi/location-update"
            val body = RequestBody.create("application/json".toMediaType(), json.toString())
            val request = Request.Builder()
                .url(url)
                .post(body)
                .addHeader("Cookie", sessionCookie)
                .build()

            OkHttpClient().newCall(request).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    Log.e("MainActivity", "Failed to send location update: ${e.message}")
                }
                override fun onResponse(call: Call, response: Response) {
                    if (response.code == 401) {
                        runOnUiThread {
                            Toast.makeText(this@MainActivity, "Session expired. Please login again.", Toast.LENGTH_SHORT).show()
                            startActivity(Intent(this@MainActivity, LoginActivity::class.java))
                            finish()
                        }
                        return
                    }
                    if (!response.isSuccessful) {
                        Log.e("MainActivity", "Failed to send location update: ${response.code}")
                    }
                }
            })
        } catch (e: Exception) {
            Log.e("MainActivity", "Error sending location update: ${e.message}")
        }
    }

    private fun registerNetworkReceiver() {
        val filter = IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION)
        registerReceiver(object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                val network = cm.activeNetwork
                val capabilities = cm.getNetworkCapabilities(network)
                isOnline = capabilities != null && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                updateOnlineStatus()
            }
        }, filter)
    }

    private fun updateOnlineStatus() {
        runOnUiThread {
            recordButton.isEnabled = isOnline && !isLowBattery
            statusText.text = if (isOnline) "Online" else "Offline"
        }
    }

    private fun checkBatteryStatus() {
        val bm = getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val batteryPct = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        isLowBattery = batteryPct < 20
        adjustLocationInterval()
    }

    private fun adjustLocationInterval() {
        // Lower frequency if low battery
        if (isLowBattery) {
            // e.g., set interval to 10 seconds
            // Constants.LOCATION_UPDATE_INTERVAL = 10000L
            statusText.text = "Low battery mode: reduced tracking"
        } else {
            // Constants.LOCATION_UPDATE_INTERVAL = 3000L
        }
    }

    private fun startLocationUpdatesEvery3Sec() {
        val fusedLocationClient = com.google.android.gms.location.LocationServices.getFusedLocationProviderClient(this)
        val handler = android.os.Handler(mainLooper)
        val runnable = object : Runnable {
            override fun run() {
                try {
                    fusedLocationClient.lastLocation.addOnSuccessListener { location: Location? ->
                        if (location != null) {
                            lastKnownLocation = location
                            updateCurrentLocationText(location)
                            updateStopListWithFilter(findViewById<EditText>(R.id.stopSearchEdit)?.text?.toString() ?: "")
                            // Only send location update if threshold is crossed
                            if (shouldSendLocation(location)) {
                                sendLocationUpdate(location.latitude, location.longitude)
                                lastSentLocation = Location(location) // Make a copy
                            }
                        }
                    }
                } catch (e: Exception) {}
                handler.postDelayed(this, Constants.LOCATION_UPDATE_INTERVAL)
            }
        }
        handler.post(runnable)
    }

    private fun shouldSendLocation(newLocation: Location): Boolean {
        val last = lastSentLocation
        return last == null || newLocation.distanceTo(last) > Constants.DISTANCE_THRESHOLD_METERS
    }

    private fun updateCurrentLocationText(location: Location) {
        currentLocationText.text = "${location.latitude.format(6)}, ${location.longitude.format(6)}"
    }

    private fun Double.format(digits: Int) = "%.${digits}f".format(this)

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1002) {
            val fineLocationGranted = permissions.indexOf(Manifest.permission.ACCESS_FINE_LOCATION).let { idx ->
                idx != -1 && grantResults.getOrNull(idx) == PackageManager.PERMISSION_GRANTED
            }
            val backgroundLocationGranted = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                permissions.indexOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION).let { idx ->
                    idx != -1 && grantResults.getOrNull(idx) == PackageManager.PERMISSION_GRANTED
                }
            } else true
            val foregroundServiceLocationGranted = if (android.os.Build.VERSION.SDK_INT >= 34) {
                permissions.indexOf(Manifest.permission.FOREGROUND_SERVICE_LOCATION).let { idx ->
                    idx != -1 && grantResults.getOrNull(idx) == PackageManager.PERMISSION_GRANTED
                }
            } else true
            if (fineLocationGranted && backgroundLocationGranted && foregroundServiceLocationGranted) {
                startLocationTracking()
            } else {
                // Not all permissions granted, show a dialog to guide the user to settings
                androidx.appcompat.app.AlertDialog.Builder(this)
                    .setTitle("Location Permission Required")
                    .setMessage("This app requires location permission to function. Please enable it in settings.")
                    .setPositiveButton("Open Settings") { _, _ ->
                        val intent = Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                        intent.data = android.net.Uri.fromParts("package", packageName, null)
                        startActivity(intent)
                    }
                    .setNegativeButton("Exit") { _, _ ->
                        finish()
                    }
                    .show()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        Log.d("MainActivity", "onResume: stopping LocationService")
        if (isLocationServiceRunning) {
            stopService(Intent(this, LocationService::class.java))
            isLocationServiceRunning = false
        }
    }

    override fun onPause() {
        super.onPause()
        Log.d("MainActivity", "onPause: starting LocationService")
        if (isTracking && !isLocationServiceRunning) {
            val intent = Intent(this, LocationService::class.java).apply {
                putExtra("update_interval", Constants.LOCATION_UPDATE_INTERVAL)
            }
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
            isLocationServiceRunning = true
        }
    }
} 