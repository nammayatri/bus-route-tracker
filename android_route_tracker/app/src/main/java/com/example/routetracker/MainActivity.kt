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
import com.example.routetracker.PermissionHelper
import com.example.routetracker.ToastHelper
import com.example.routetracker.SessionHelper
import com.example.routetracker.LocationHelper
import com.example.routetracker.NetworkHelper
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.card.MaterialCardView
import okhttp3.RequestBody.Companion.toRequestBody
import android.view.View
import android.widget.ImageButton
import com.google.android.gms.location.*

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
    private lateinit var toggleLocationButton: MaterialButton
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
    private var isBackendUpdateInProgress = false

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationCallback: LocationCallback

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        routeField = findViewById(R.id.routeField)
        recordButton = findViewById(R.id.recordButton)
        statusText = findViewById(R.id.statusText)
        logoutButton = findViewById(R.id.logoutButton)
        toggleLocationButton = findViewById(R.id.toggleLocationButton)
        currentLocationText = findViewById(R.id.currentLocationText)
        stopField = findViewById(R.id.stopField)
        topStopsLayout = findViewById(R.id.topStopsLayout)

        setupUI()
        checkAndRequestPermissions()
        registerNetworkReceiver()
        checkBatteryStatus()
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                val location = locationResult.lastLocation
                if (location != null) {
                    lastKnownLocation = location
                    updateCurrentLocationText(location)
                    updateStopListWithFilter(findViewById<EditText>(R.id.stopSearchEdit)?.text?.toString() ?: "")
                    // Do NOT sendLocationUpdate from MainActivity; backend updates are handled by LocationService
                }
            }
        }
        startLocationUpdatesEvery3Sec()

        logoutButton.setOnClickListener {
            getSharedPreferences("Auth", MODE_PRIVATE).edit().clear().apply()
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
        }

        toggleLocationButton.setOnClickListener {
            if (isTracking) {
                stopLocationTracking()
            } else {
                startLocationTracking()
            }
        }

        routeField.setOnClickListener { showRoutePickerDialog() }

        recordButton.setOnClickListener {
            // Show Material loader dialog while fetching location
            val loaderView = layoutInflater.inflate(R.layout.dialog_loader, null)
            val loaderDialog = MaterialAlertDialogBuilder(this)
                .setView(loaderView)
                .setCancelable(false)
                .create()
            loaderDialog.show()
            LocationHelper.fetchFreshOrLastLocation(this) { location ->
                loaderDialog.dismiss()
                if (location != null) {
                    lastKnownLocation = location
                    confirmAndRecordStop()
                } else {
                    ToastHelper.show(this, "Could not fetch current location. Please try again.")
                }
            }
        }

        stopField.setOnClickListener {
            if (selectedRouteId != null) {
                showStopPickerDialog()
            } else {
                ToastHelper.show(this, "Please select a route first")
            }
        }
    }

    private fun setupUI() {
        fetchRoutes()
    }

    private fun checkAndRequestPermissions() {
        if (!PermissionHelper.hasAllLocationPermissions(this)) {
            PermissionHelper.requestLocationPermissions(this, 1002)
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
                // Do NOT remove MainActivity's location updates; keep UI always live
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
                updateOnlineStatus()
                Toast.makeText(this, "Location tracking started", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Log.e("MainActivity", "Failed to start location tracking: ", e)
                Toast.makeText(this, "Failed to start location tracking", Toast.LENGTH_SHORT).show()
                isTracking = false
                updateUIForTrackingState()
                updateOnlineStatus()
            }
        }
    }

    private fun stopLocationTracking() {
        if (isTracking) {
            try {
                stopService(Intent(this, LocationService::class.java))
                // Do NOT re-register location updates; UI updates are always active
                isTracking = false
                updateUIForTrackingState()
                updateOnlineStatus()
                Toast.makeText(this, "Location tracking stopped", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Log.e("MainActivity", "Failed to stop location tracking: ", e)
                Toast.makeText(this, "Failed to stop location tracking", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun updateUIForTrackingState() {
        runOnUiThread {
            if (isTracking) {
                statusText.text = "Location tracking is active"
                recordButton.isEnabled = true
                toggleLocationButton.text = "Stop Location Updates"
                toggleLocationButton.setBackgroundColor(resources.getColor(android.R.color.holo_orange_dark, null))
            } else {
                statusText.text = "Location tracking is stopped"
                recordButton.isEnabled = false
                toggleLocationButton.text = "Start Location Updates"
                toggleLocationButton.setBackgroundColor(resources.getColor(android.R.color.holo_green_dark, null))
            }
        }
    }

    private fun getSessionCookie(): String? {
        return getSharedPreferences("Auth", MODE_PRIVATE).getString("session", null)
    }

    private fun fetchRoutes() {
        val sessionCookie = getSessionCookie()
        if (sessionCookie == null) {
            SessionHelper.handleSessionExpired(this)
            return
        }
        val url = "${Constants.BASE_URL}/routeTrackerApi/routes"
        NetworkHelper.authenticatedRequest(
            url,
            "GET",
            sessionCookie,
            onSuccess = { responseBody ->
                val arr = JSONArray(responseBody)
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
                    routeField.text = routeNames[0]
                    stopField.isEnabled = false
                    stopField.text = "Select stop..."
                }
            },
            onError = { e ->
                runOnUiThread {
                    ToastHelper.show(this, "Failed to load routes: ${e.message}")
                }
            }
        )
    }

    private fun fetchStops(routeId: String) {
        val sessionCookie = getSessionCookie()
        if (sessionCookie == null) {
            SessionHelper.handleSessionExpired(this)
            return
        }
        val url = "${Constants.BASE_URL}/routeTrackerApi/stops?route_id=$routeId"
        NetworkHelper.authenticatedRequest(
            url,
            "GET",
            sessionCookie,
            onSuccess = { responseBody ->
                val arr = JSONArray(responseBody)
                stops = (0 until arr.length()).map { arr.getJSONObject(it) }
                runOnUiThread {
                    updateStopListWithFilter("")
                    stopField.isEnabled = true
                    stopField.text = "Select stop..."
                }
            },
            onError = { e ->
                runOnUiThread {
                    ToastHelper.show(this, "Failed to load stops: ${e.message}")
                }
            }
        )
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
        val card = MaterialCardView(this)
        card.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(0, 0, 0, 16) }
        card.radius = 18f
        card.cardElevation = 6f
        card.isClickable = true
        card.isFocusable = true
        card.setRippleColorResource(R.color.primary)
        card.setCardBackgroundColor(resources.getColor(
            if (selectedStop?.stop_id == nearest.stop_id) R.color.highlight else android.R.color.white, null))

        val container = LinearLayout(this)
        container.orientation = LinearLayout.VERTICAL
        container.setPadding(0, 0, 0, 0)
        container.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )

        // Add 'Closest stop' label
        val closestLabel = TextView(this)
        closestLabel.text = "Closest stop"
        closestLabel.setTextColor(resources.getColor(R.color.gray, null))
        closestLabel.textSize = 12f
        closestLabel.setTypeface(null, android.graphics.Typeface.BOLD)
        closestLabel.setPadding(0, 0, 0, 2)
        closestLabel.gravity = android.view.Gravity.CENTER
        container.addView(closestLabel)

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

        card.setOnClickListener {
            selectedStop = nearest
            stopField.text = nearest.stopName
            updateTopStopsChips(stops)
        }

        container.addView(stopNameView)
        container.addView(distanceView)
        card.addView(container)
        topStopsLayout.addView(card)
    }

    private fun getCurrentLocation(): Location? {
        return lastKnownLocation
    }


    private fun showDropdownDialog(
        hint: String,
        items: List<Any>,
        adapterProvider: (List<Any>, (Any) -> Unit) -> RecyclerView.Adapter<*>,
        onItemSelected: (Any) -> Unit,
        filterPredicate: (Any, String) -> Boolean,
        updateCallback: ((List<Any>) -> Unit)? = null,
        dialogTitle: String = "Select"
    ) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_dropdown_common, null)
        val searchEdit = dialogView.findViewById<EditText>(R.id.dropdownSearchEdit)
        val recyclerView = dialogView.findViewById<RecyclerView>(R.id.dropdownRecyclerView)
        val textInputLayout = dialogView.findViewById<com.google.android.material.textfield.TextInputLayout>(R.id.dropdownTextInputLayout)
        val titleView = dialogView.findViewById<TextView>(R.id.dropdownDialogTitle)
        val closeBtn = dialogView.findViewById<ImageButton>(R.id.dropdownDialogClose)
        val emptyState = dialogView.findViewById<TextView>(R.id.dropdownEmptyState)
        textInputLayout.hint = hint
        titleView.text = dialogTitle
        val dialog = AlertDialog.Builder(this, R.style.TransparentDialog)
            .setView(dialogView)
            .create()
        closeBtn.setOnClickListener { dialog.dismiss() }

        var currentItems = items
        val adapter = adapterProvider(currentItems) { selected ->
            onItemSelected(selected)
            updateCallback?.invoke(currentItems)
            dialog.dismiss()
        }
        recyclerView.adapter = adapter
        recyclerView.layoutManager = LinearLayoutManager(this)

        fun updateEmptyState(filteredCount: Int) {
            emptyState.visibility = if (filteredCount == 0) View.VISIBLE else View.GONE
            recyclerView.visibility = if (filteredCount == 0) View.GONE else View.VISIBLE
        }

        searchEdit.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) {}
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val filtered = currentItems.filter { filterPredicate(it, s.toString()) }
                updateEmptyState(filtered.size)
                when (adapter) {
                    is RouteAdapter -> {
                        @Suppress("UNCHECKED_CAST")
                        adapter.updateRoutes(filtered as List<RouteDisplayItem>)
                    }
                    is StopAdapter -> {
                        @Suppress("UNCHECKED_CAST")
                        adapter.updateStops(filtered as List<StopDisplayItem>)
                    }
                }
            }
        })
        updateEmptyState(currentItems.size)
        dialog.show()
    }

    private fun showRoutePickerDialog() {
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
        showDropdownDialog(
            hint = "Search route...",
            items = routeItems,
            adapterProvider = { items, onClick -> RouteAdapter(items as List<RouteDisplayItem>, onClick as (RouteDisplayItem) -> Unit) },
            onItemSelected = { selected ->
                val selectedRoute = selected as RouteDisplayItem
                routeField.text = "${selectedRoute.routeCode} | ${selectedRoute.routeStart} -> ${selectedRoute.routeEnd}"
                selectedRouteId = selectedRoute.routeCode
                getSharedPreferences("Auth", MODE_PRIVATE).edit().putString("route_id", selectedRoute.routeCode).apply()
                fetchStops(selectedRoute.routeCode)
            },
            filterPredicate = { item, query ->
                val route = item as RouteDisplayItem
                route.routeCode.contains(query, true) ||
                route.routeStart.contains(query, true) ||
                route.routeEnd.contains(query, true)
            },
            dialogTitle = "Select Route"
        )
    }

    private fun showStopPickerDialog() {
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
        showDropdownDialog(
            hint = "Search stop...",
            items = stopItemsWithTop2,
            adapterProvider = { items, onClick -> StopAdapter(items as List<StopDisplayItem>, onClick as (StopDisplayItem) -> Unit) },
            onItemSelected = { selected ->
                val stop = selected as StopDisplayItem
                selectedStop = stop
                stopField.text = stop.stopName
                updateTopStopsChips(stopItemsWithTop2)
            },
            filterPredicate = { item, query ->
                val stop = item as StopDisplayItem
                stop.stopName.contains(query, true)
            },
            updateCallback = { updateTopStopsChips(stopItemsWithTop2) },
            dialogTitle = "Select Stop"
        )
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
            ToastHelper.show(this, "Please select a stop or enter a stop name")
            return
        }
        if (!isTracking) {
            ToastHelper.show(this, "Please start location tracking first")
            return
        }
        if (selectedRouteId == null) {
            ToastHelper.show(this, "Please select a route")
            return
        }
        val route = routes.find { it.optString("route_code", "") == selectedRouteId }
        if (route == null) {
            ToastHelper.show(this, "Invalid route selection")
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
            val body = json.toString().toRequestBody("application/json".toMediaType())
            val sessionCookie = getSessionCookie()
            if (sessionCookie == null) {
                SessionHelper.handleSessionExpired(this)
                return
            }
            NetworkHelper.authenticatedRequest(
                url,
                "POST",
                sessionCookie,
                body,
                onSuccess = {
                    runOnUiThread {
                        val rootView = findViewById<View>(android.R.id.content)
                        Snackbar.make(rootView, "Stop recorded successfully", Snackbar.LENGTH_LONG).show()
                    }
                },
                onError = { e ->
                    runOnUiThread {
                        ToastHelper.show(this, "Failed to record stop: ${e.message}")
                    }
                }
            )
        } catch (e: Exception) {
            Log.e("MainActivity", "Error recording stop: ${e.message}")
            ToastHelper.show(this, "Error recording stop: ${e.message}")
        }
    }

    private fun registerNetworkReceiver() {
        // TODO: For Android N+ use ConnectivityManager.NetworkCallback for network changes. This is required for future-proofing as CONNECTIVITY_ACTION is deprecated.
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
            val statusDot = findViewById<View>(R.id.statusCard).findViewById<View>(R.id.statusDot)
            if (isTracking) {
                statusText.text = "Online"
                statusDot?.setBackgroundResource(R.drawable.green_dot)
            } else {
                statusText.text = "Location tracking off"
                statusDot?.setBackgroundResource(R.drawable.red_dot)
            }
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
        val locationRequest = LocationRequest.create().apply {
            interval = Constants.LOCATION_UPDATE_INTERVAL
            fastestInterval = Constants.LOCATION_UPDATE_INTERVAL
            priority = LocationRequest.PRIORITY_HIGH_ACCURACY
        }
        fusedLocationClient.requestLocationUpdates(locationRequest, locationCallback, mainLooper)
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

    override fun onDestroy() {
        super.onDestroy()
        fusedLocationClient.removeLocationUpdates(locationCallback)
    }
} 