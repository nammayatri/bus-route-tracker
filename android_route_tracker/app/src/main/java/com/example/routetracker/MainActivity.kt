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
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.card.MaterialCardView
import okhttp3.RequestBody.Companion.toRequestBody
import android.view.View
import android.widget.ImageButton
import com.google.android.gms.location.*
import android.widget.Spinner
import android.view.LayoutInflater
import android.widget.FrameLayout
import android.view.WindowManager
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.widget.Button
import android.widget.ListView
import com.google.android.material.bottomsheet.BottomSheetDialog
import android.widget.AutoCompleteTextView
import android.view.inputmethod.InputMethodManager
import android.provider.Settings
import android.widget.ScrollView
import android.view.ViewGroup
import android.location.LocationManager
import android.net.Uri
import com.example.routetracker.RouteDisplayItem
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts

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

    private lateinit var routeSelectButton: MaterialButton
    private lateinit var submitButton: Button
    private var routes: List<JSONObject> = listOf()
    private var stops: List<JSONObject> = listOf()
    private var selectedRouteId: String? = null
    private var selectedRouteDisplay: String = "Select Route"
    private var lastKnownLocation: Location? = null
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationCallback: LocationCallback
    private var routePickerDialog: BottomSheetDialog? = null
    private var routePickerAdapter: RecyclerView.Adapter<*>? = null
    private var routePickerRecyclerView: RecyclerView? = null
    private var backgroundDialogShown = false
    private lateinit var settingsLauncher: ActivityResultLauncher<Intent>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Check if location is enabled, prompt if not
        if (!isLocationEnabled()) {
            promptEnableLocation()
        }
        // Check if location permission is granted, prompt if not
        if (!PermissionHelper.hasForegroundLocationPermission(this)) {
            promptLocationPermission()
        }

        routeSelectButton = findViewById(R.id.routeSelectButton)
        submitButton = findViewById(R.id.submitButton)

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                lastKnownLocation = locationResult.lastLocation
            }
        }
        startLocationUpdates()
        fetchRoutes()

        routeSelectButton.text = selectedRouteDisplay
        routeSelectButton.setOnClickListener { showRoutePickerDialog() }
        submitButton.setOnClickListener { handleSubmit() }

        // Fetch configs from backend before starting location service
        ConfigManager.fetchConfigs( "chennai", "bus") {
            runOnUiThread {
                if (PermissionHelper.hasForegroundLocationPermission(this)) {
                    val serviceIntent = Intent(this, LocationService::class.java)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        startForegroundService(serviceIntent)
                    } else {
                        startService(serviceIntent)
                    }
                } else {
                    PermissionHelper.requestForegroundLocationPermission(this, 1001)
                }
            }
        }

        backgroundDialogShown = false
        settingsLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (PermissionHelper.hasBackgroundLocationPermission(this)) {
                startLocationUpdates()
                val serviceIntent = Intent(this, LocationService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(serviceIntent)
                } else {
                    startService(serviceIntent)
                }
            } else {
                backgroundDialogShown = false
                Toast.makeText(this, "Background location not granted. You can enable it in settings anytime.", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun startLocationUpdates() {
        val locationRequest = LocationRequest.create().apply {
            interval = 3000L
            fastestInterval = 3000L
            priority = LocationRequest.PRIORITY_HIGH_ACCURACY
        }
        fusedLocationClient.requestLocationUpdates(locationRequest, locationCallback, mainLooper)
    }

    private fun fetchRoutes() {
        val url = "${Constants.BASE_URL}/routeTrackerApi/routes"
        NetworkHelper.authenticatedRequest(
            url,
            "GET",
            onSuccess = { responseBody ->
                try {
                    val arr = JSONArray(responseBody)
                    routes = (0 until arr.length()).map { arr.getJSONObject(it) }
                    runOnUiThread {
                        // If the route picker dialog is open, update its adapter
                        if (routePickerDialog?.isShowing == true && routePickerAdapter != null && routePickerRecyclerView != null) {
                            // Rebuild the routeItems and update the adapter's data
                            val newRouteItems = routes.map {
                                val code = it.optString("route_code", "")
                                val start = it.optString("route_start_point", "")
                                val end = it.optString("route_end_point", "")
                                val number = if (it.has("route_number")) it.optString("route_number", null) else null
                                RouteDisplayItem(
                                    routeCode = code,
                                    routeStart = start,
                                    routeEnd = end,
                                    routeNumber = number
                                )
                            }
                            // If using RouteAdapter, update its data
                            (routePickerAdapter as? RouteAdapter)?.updateRoutes(newRouteItems)
                        }
                    }
                } catch (e: Exception) {
                    runOnUiThread {
                        Toast.makeText(this, "Error parsing routes: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            },
            onError = { e ->
                try {
                    runOnUiThread {
                        Toast.makeText(this, "Failed to load routes: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                } catch (ex: Exception) {
                    Log.e("MainActivity", "Error handling routes error: ${ex.message}")
                }
            }
        )
    }

    private fun fetchStops(routeId: String) {
        val url = "${Constants.BASE_URL}/routeTrackerApi/stops?route_id=$routeId"
        NetworkHelper.authenticatedRequest(
            url,
            "GET",
            onSuccess = { responseBody ->
                try {
                    val arr = JSONArray(responseBody)
                    stops = (0 until arr.length()).map { arr.getJSONObject(it) }
                } catch (e: Exception) {
                    runOnUiThread {
                        Toast.makeText(this, "Error parsing stops: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            },
            onError = { e ->
                try {
                    runOnUiThread {
                        Toast.makeText(this, "Failed to load stops: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                } catch (ex: Exception) {
                    Log.e("MainActivity", "Error handling stops error: ${ex.message}")
                }
            }
        )
    }

    private fun handleSubmit() {
        val routeId = selectedRouteId
        val location = lastKnownLocation
        if (routeId == null) {
            Toast.makeText(this, "Please select a route", Toast.LENGTH_SHORT).show()
            return
        }
        if (location == null) {
            Toast.makeText(this, "Location not available", Toast.LENGTH_SHORT).show()
            return
        }
        val nearest = getNearestStop(location)
        if (stops.isEmpty()) {
            showNoStopFallbackDialog()
        } else if (nearest != null && nearest.distanceMeters <= ConfigManager.stopDistanceRadiusThresholdMeters) {
            showConfirmStopDialog(nearest)
        } else {
            showNoStopFallbackDialog()
        }
    }

    private fun getNearestStop(location: Location): StopDisplayItem? {
        if (stops.isEmpty()) return null
        return stops.map { stop ->
            val stopLat = stop.optDouble("lat", 0.0)
            val stopLon = stop.optDouble("lon", 0.0)
            val result = FloatArray(1)
            android.location.Location.distanceBetween(location.latitude, location.longitude, stopLat, stopLon, result)
            StopDisplayItem(
                stopName = stop.optString("stop_name", ""),
                distanceMeters = result[0].toDouble(),
                isTop3 = false,
                stop_id = stop.optString("stop_id", ""),
                lat = stopLat,
                lon = stopLon
            )
        }.minByOrNull { it.distanceMeters }
    }

    private fun showConfirmStopDialog(stop: StopDisplayItem) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_confirm_stop, null)
        val stopNameView = dialogView.findViewById<TextView>(R.id.nearestStopName)
        stopNameView.text = stop.stopName
        val stopDistanceView = dialogView.findViewById<TextView>(R.id.nearestStopDistance)
        val distStr = if (stop.distanceMeters >= 1000) {
            String.format("%.2f km", stop.distanceMeters / 1000.0)
        } else {
            String.format("%.0f m", stop.distanceMeters)
        }
        stopDistanceView.text = distStr + " away"

        val bottomSheetDialog = com.google.android.material.bottomsheet.BottomSheetDialog(this)
        bottomSheetDialog.setContentView(dialogView)
        // Set bottom sheet height to half the screen
        dialogView.post {
            val bottomSheet = bottomSheetDialog.delegate.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
            bottomSheet?.layoutParams?.height = (resources.displayMetrics.heightPixels * 0.55).toInt()
            bottomSheet?.requestLayout()
        }
        bottomSheetDialog.show()

        dialogView.findViewById<ImageButton>(R.id.correctButton).setOnClickListener {
            logStop(selectedRouteId!!, stop.stop_id, stop.stopName, lastKnownLocation!!.latitude, lastKnownLocation!!.longitude, "STOP_RECORD")
            bottomSheetDialog.dismiss()
        }
        dialogView.findViewById<ImageButton>(R.id.incorrectButton).setOnClickListener {
            logStop(selectedRouteId!!, stop.stop_id, stop.stopName, lastKnownLocation!!.latitude, lastKnownLocation!!.longitude, "STOP_RECORD_INCORRECT")
            bottomSheetDialog.dismiss()
            showNoStopFallbackDialog()
        }
    }

    private fun showNoStopFallbackDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_no_stop_fallback, null)
        val stopSelectButton = dialogView.findViewById<MaterialButton>(R.id.stopSelectButton)
        val manualStopInput = dialogView.findViewById<EditText>(R.id.manualStopInput)
        val submitButton = dialogView.findViewById<Button>(R.id.submitFallbackButton)
        val skipButton = dialogView.findViewById<Button>(R.id.skipFallbackButton)
        val nearestStopDistanceView = dialogView.findViewById<TextView>(R.id.nearestStopDistance)
        // Prepare stop list sorted by distance
        val location = lastKnownLocation
        val stopItems = stops.map { stop ->
            val stopLat = stop.optDouble("lat", 0.0)
            val stopLon = stop.optDouble("lon", 0.0)
            val result = FloatArray(1)
            val dist = if (location != null) {
                android.location.Location.distanceBetween(location.latitude, location.longitude, stopLat, stopLon, result)
                result[0].toDouble()
            } else 0.0
            StopDisplayItem(
                stopName = stop.optString("stop_name", ""),
                distanceMeters = dist,
                isTop3 = false,
                stop_id = stop.optString("stop_id", ""),
                lat = stopLat,
                lon = stopLon
            )
        }.sortedBy { it.distanceMeters }
        // Set nearest stop distance if available
        if (nearestStopDistanceView != null) {
            if (stopItems.isNotEmpty()) {
                val nearest = stopItems[0]
                val distStr = if (nearest.distanceMeters >= 1000) {
                    String.format("%.2f km", nearest.distanceMeters / 1000.0)
                } else {
                    String.format("%.0f m", nearest.distanceMeters)
                }
                nearestStopDistanceView.text = "Nearest stop: $distStr away"
                nearestStopDistanceView.visibility = View.VISIBLE
            } else {
                nearestStopDistanceView.text = ""
                nearestStopDistanceView.visibility = View.GONE
            }
        }
        // Custom adapter for spinner: show stop name and distance
        val stopDisplayList = stopItems.map {
            val distStr = if (it.distanceMeters >= 1000) {
                String.format("%.2f km", it.distanceMeters / 1000.0)
            } else {
                String.format("%.0f m", it.distanceMeters)
            }
            "${it.stopName} ($distStr)"
        }
        val adapter = object : ArrayAdapter<String>(this, android.R.layout.simple_dropdown_item_1line, stopDisplayList) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val v = super.getView(position, convertView, parent)
                (v as? TextView)?.apply {
                    maxLines = 2
                    textSize = 20f
                }
                return v
            }
        }
        manualStopInput.textSize = 20f
        var selectedStop: StopDisplayItem? = null
        stopSelectButton.setOnClickListener {
            // Show bottom sheet stop picker
            val sheetDialog = BottomSheetDialog(this)
            val sheetView = layoutInflater.inflate(R.layout.dialog_route_picker, null)
            val searchEdit = sheetView.findViewById<EditText>(R.id.routeSearchEdit)
            searchEdit.hint = "Search stop..."
            val recyclerView = sheetView.findViewById<RecyclerView>(R.id.routeRecyclerView)
            recyclerView.layoutManager = LinearLayoutManager(this)

            // Add manual entry field and button (hidden by default)
            val manualEntryEdit = EditText(this).apply {
                hint = "Enter stop name"
                textSize = 18f
                setPadding(24, 24, 24, 24)
                background = getDrawable(R.drawable.rounded_field_bg)
                visibility = View.GONE
            }
            val manualEntryButton = MaterialButton(this).apply {
                text = "Select"
                setTextColor(resources.getColor(android.R.color.white, null))
                setBackgroundColor(resources.getColor(R.color.submit_button_bg, null))
                setPadding(24, 16, 24, 16)
                visibility = View.GONE
            }
            val noResultsText = TextView(this).apply {
                text = "No stops found"
                textSize = 18f
                setTextColor(resources.getColor(android.R.color.holo_red_dark, null))
                setPadding(24, 32, 24, 8)
                visibility = View.GONE
            }
            // Add to the sheetView's root LinearLayout
            val sheetLinearLayout = (sheetView as ScrollView).getChildAt(0) as LinearLayout
            sheetLinearLayout.addView(noResultsText)
            sheetLinearLayout.addView(manualEntryEdit)
            sheetLinearLayout.addView(manualEntryButton)

            // Prepare stop items for display
            val stopItemsForPicker = stopItems
            var filtered = stopItemsForPicker
            val adapter = object : RecyclerView.Adapter<RouteViewHolder>() {
                var items = filtered
                override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RouteViewHolder {
                    val v = LayoutInflater.from(parent.context).inflate(R.layout.item_route, parent, false)
                    return RouteViewHolder(v)
                }
                override fun onBindViewHolder(holder: RouteViewHolder, position: Int) {
                    val stop = items[position]
                    holder.codeView.text = stop.stopName
                    holder.startView.text = if (stop.distanceMeters >= 1000) String.format("%.2f km", stop.distanceMeters / 1000.0) else String.format("%.0f m", stop.distanceMeters)
                    holder.endView?.let { it.visibility = View.GONE }
                    holder.itemView.setOnClickListener {
                        // Hide keyboard before dismissing
                        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                        imm.hideSoftInputFromWindow(sheetView.windowToken, 0)
                        selectedStop = stop
                        stopSelectButton.text = "${stop.stopName} (${if (stop.distanceMeters >= 1000) String.format("%.2f km", stop.distanceMeters / 1000.0) else String.format("%.0f m", stop.distanceMeters)})"
                        sheetDialog.dismiss()
                    }
                }
                override fun getItemCount() = items.size
            }
            recyclerView.adapter = adapter
            searchEdit.addTextChangedListener(object : TextWatcher {
                override fun afterTextChanged(s: Editable?) {}
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    val query = s.toString().trim().lowercase()
                    val isInt = query.toIntOrNull() != null
                    val filteredList = stopItemsForPicker.filter {
                        it.stopName.lowercase().contains(query)
                    }
                    val sorted = if (isInt) {
                        filteredList.sortedBy { it.stopName }
                    } else {
                        filteredList.sortedBy { it.stopName }
                    }
                    adapter.items = sorted
                    adapter.notifyDataSetChanged()
                    // Show/hide manual entry and no results
                    if (sorted.isEmpty()) {
                        recyclerView.visibility = View.GONE
                        noResultsText.visibility = View.VISIBLE
                        manualEntryEdit.visibility = View.VISIBLE
                        manualEntryButton.visibility = View.VISIBLE
                    } else {
                        recyclerView.visibility = View.VISIBLE
                        noResultsText.visibility = View.GONE
                        manualEntryEdit.visibility = View.GONE
                        manualEntryButton.visibility = View.GONE
                    }
                }
            })
            manualEntryButton.setOnClickListener {
                val manualName = manualEntryEdit.text.toString().trim()
                if (manualName.isNotEmpty()) {
                    // Hide keyboard before dismissing
                    val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                    imm.hideSoftInputFromWindow(sheetView.windowToken, 0)
                    selectedStop = StopDisplayItem(
                        stopName = manualName,
                        distanceMeters = 0.0,
                        isTop3 = false,
                        stop_id = "manual",
                        lat = 0.0,
                        lon = 0.0
                    )
                    stopSelectButton.text = manualName
                    sheetDialog.dismiss()
                } else {
                    Toast.makeText(this, "Please enter a stop name", Toast.LENGTH_SHORT).show()
                }
            }
            sheetDialog.setContentView(sheetView)
            // Fix: Ensure bottom sheet resizes with keyboard
            sheetDialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            // Allow dismiss on outside touch
            sheetDialog.setCanceledOnTouchOutside(true)
            sheetDialog.show()
        }
        // Create the fallback dialog and assign to a variable
        val bottomSheetDialog = com.google.android.material.bottomsheet.BottomSheetDialog(this)
        bottomSheetDialog.setContentView(dialogView)
        // Set bottom sheet height to half the screen
        dialogView.post {
            val bottomSheet = bottomSheetDialog.delegate.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
            bottomSheet?.layoutParams?.height = (resources.displayMetrics.heightPixels * 0.56).toInt()
            bottomSheet?.requestLayout()
        }
        submitButton.setOnClickListener {
            val manualStop = manualStopInput.text.toString().trim()
            if (manualStop.isNotEmpty()) {
                logStop(selectedRouteId!!, "manual", manualStop, lastKnownLocation?.latitude ?: 0.0, lastKnownLocation?.longitude ?: 0.0, "STOP_RECORD_TYPED")
            } else if (selectedStop != null) {
                logStop(selectedRouteId!!, selectedStop!!.stop_id, selectedStop!!.stopName, lastKnownLocation?.latitude ?: 0.0, lastKnownLocation?.longitude ?: 0.0, "STOP_RECORD_SELECTED")
            } else {
                Toast.makeText(this, "Please select or enter a stop", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            bottomSheetDialog.dismiss()
        }
        skipButton.setOnClickListener {
            logStop(selectedRouteId!!, "", "", lastKnownLocation?.latitude ?: 0.0, lastKnownLocation?.longitude ?: 0.0, "STOP_RECORD_SKIP")
            bottomSheetDialog.dismiss()
        }
        bottomSheetDialog.show()
    }

    private fun logStop(routeId: String, stopId: String, stopName: String, lat: Double, lon: Double, type: String) {
        try {
            val deviceId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)
            val appVersion = packageManager.getPackageInfo(packageName, 0).versionName
            val deviceName = Build.MODEL
            val json = JSONObject().apply {
                put("route_id", routeId)
                put("stop_id", stopId)
                put("stop_name", stopName)
                put("lat", lat)
                put("lon", lon)
                put("type", type)
                put("device_id", deviceId ?: JSONObject.NULL)
                put("device_name", deviceName ?: JSONObject.NULL)
                put("app_version", appVersion ?: JSONObject.NULL)
            }
            val url = "${Constants.BASE_URL}/routeTrackerApi/record"
            val body = json.toString().toRequestBody("application/json".toMediaType())
            NetworkHelper.authenticatedRequest(
                url,
                "POST",
                body,
                onSuccess = {
                    try {
                        runOnUiThread {
                            Toast.makeText(this, "Stop recorded successfully!", Toast.LENGTH_SHORT).show()
                        }
                    } catch (e: Exception) {
                        Log.e("MainActivity", "Error handling stop success: ${e.message}")
                    }
                },
                onError = { e ->
                    try {
                        runOnUiThread {
                            Toast.makeText(this, "Failed to record stop: ${e.message}", Toast.LENGTH_SHORT).show()
                        }
                    } catch (ex: Exception) {
                        Log.e("MainActivity", "Error handling stop error: ${ex.message}")
                    }
                }
            )
        } catch (e: Exception) {
            Log.e("MainActivity", "Error logging stop: ${e.message}")
            Toast.makeText(this, "Error logging stop: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showRoutePickerDialog() {
        val dialog = BottomSheetDialog(this)
        val sheetView = layoutInflater.inflate(R.layout.dialog_route_picker, null)
        val searchEdit = sheetView.findViewById<EditText>(R.id.routeSearchEdit)
        val recyclerView = sheetView.findViewById<RecyclerView>(R.id.routeRecyclerView)
        recyclerView.layoutManager = LinearLayoutManager(this)
        val loadingText = TextView(this).apply {
            text = "Loading routes..."
            textSize = 18f
            setPadding(32, 48, 32, 48)
            gravity = android.view.Gravity.CENTER
        }
        val retryButton = Button(this).apply {
            text = "Retry"
            textSize = 18f
            setPadding(32, 24, 32, 24)
            visibility = View.GONE
        }
        // Fix: Add to the inner LinearLayout, not the ScrollView
        val innerLayout = (sheetView as ScrollView).getChildAt(0) as LinearLayout
        innerLayout.addView(loadingText)
        innerLayout.addView(retryButton)

        fun updateRoutesUI() {
            if (routes.isEmpty()) {
                recyclerView.visibility = View.GONE
                loadingText.visibility = View.VISIBLE
                retryButton.visibility = View.VISIBLE
            } else {
                recyclerView.visibility = View.VISIBLE
                loadingText.visibility = View.GONE
                retryButton.visibility = View.GONE
            }
        }

        retryButton.setOnClickListener {
            loadingText.text = "Loading routes..."
            loadingText.visibility = View.VISIBLE
            retryButton.visibility = View.GONE
            fetchRoutes()
            // Try to update UI after a short delay
            recyclerView.postDelayed({ updateRoutesUI() }, 1200)
        }

        if (routes.isEmpty()) {
            loadingText.visibility = View.VISIBLE
            retryButton.visibility = View.VISIBLE
            recyclerView.visibility = View.GONE
            fetchRoutes()
            recyclerView.postDelayed({ updateRoutesUI() }, 1200)
        } else {
            loadingText.visibility = View.GONE
            retryButton.visibility = View.GONE
            recyclerView.visibility = View.VISIBLE
        }

        fun compareNaturalSortKey(a: List<Comparable<*>>, b: List<Comparable<*>>): Int {
            for (i in a.indices) {
                val cmp = compareValues(a[i], b[i])
                if (cmp != 0) return cmp
            }
            return 0
        }

        fun computeNaturalSortKey(routeNumber: String?, routeCode: String, routeStart: String, routeEnd: String): List<Comparable<*>> {
            val str = routeNumber?.takeIf { it.isNotBlank() } ?: routeCode
            val regex = Regex("^(\\d+)([a-zA-Z]*)$")
            val match = regex.matchEntire(str.trim())
            return if (match != null) {
                val (num, suf) = match.destructured
                listOf(num.toIntOrNull() ?: Int.MAX_VALUE, suf.lowercase(), routeStart, routeEnd)
            } else {
                listOf(Int.MAX_VALUE, str.lowercase(), routeStart, routeEnd)
            }
        }

        val routeItems = routes.map {
            val code = it.optString("route_code", "")
            val start = it.optString("route_start_point", "")
            val end = it.optString("route_end_point", "")
            val number = if (it.has("route_number")) it.optString("route_number", null) else null
            RouteDisplayItem(
                routeCode = code,
                routeStart = start,
                routeEnd = end,
                routeNumber = number,
                naturalSortKey = computeNaturalSortKey(number, code, start, end)
            )
        }.sortedWith { a, b -> compareNaturalSortKey(a.naturalSortKey, b.naturalSortKey) }
        var filtered = routeItems
        val adapter = RouteAdapter(routeItems) { route ->
            selectedRouteId = route.routeCode
            selectedRouteDisplay = "${route.routeNumber ?: route.routeCode} | ${route.routeStart} -> ${route.routeEnd}"
            routeSelectButton.text = selectedRouteDisplay
            if (selectedRouteId != null) fetchStops(selectedRouteId!!)
            dialog.dismiss()
        }
        recyclerView.adapter = adapter
        // Store references for updating
        routePickerDialog = dialog
        routePickerAdapter = adapter
        routePickerRecyclerView = recyclerView
        searchEdit.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {}
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val query = s.toString()
                val filteredList = routeItems.filter {
                    (it.routeNumber ?: it.routeCode).isNotBlank() &&
                    ((it.routeNumber ?: it.routeCode).contains(query, true) ||
                     it.routeStart.contains(query, true) ||
                     it.routeEnd.contains(query, true))
                }
                adapter.updateRoutes(filteredList)
            }
        })
        dialog.setContentView(sheetView)
        // Fix: Ensure bottom sheet resizes with keyboard
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        // Allow dismiss on outside touch
        dialog.setCanceledOnTouchOutside(true)
        dialog.show()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1001) {
            if (grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                if (android.os.Build.VERSION.SDK_INT == android.os.Build.VERSION_CODES.Q) {
                    // Android 10: both permissions granted, start location updates and service
                    startLocationUpdates()
                    val serviceIntent = Intent(this, LocationService::class.java)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        startForegroundService(serviceIntent)
                    } else {
                        startService(serviceIntent)
                    }
                } else if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                    // Android 11+: only show dialog if background location is not already granted
                    if (!PermissionHelper.hasBackgroundLocationPermission(this) && !backgroundDialogShown) {
                        backgroundDialogShown = true
                        AlertDialog.Builder(this)
                            .setTitle("Background Location Required")
                            .setMessage("To enable full tracking, tap 'Open Settings', then tap 'Permissions' > 'Location' and select 'Allow all the time'.")
                            .setPositiveButton("Open Settings") { _, _ ->
                                val intent = Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                    data = Uri.fromParts("package", packageName, null)
                                }
                                settingsLauncher.launch(intent)
                            }
                            .setNegativeButton("Not Now", null)
                            .show()
                    } else if (PermissionHelper.hasBackgroundLocationPermission(this)) {
                        // Both permissions granted, start service
                        startLocationUpdates()
                        val serviceIntent = Intent(this, LocationService::class.java)
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            startForegroundService(serviceIntent)
                        } else {
                            startService(serviceIntent)
                        }
                    }
                } else {
                    // Pre-Android 10: no background location, proceed as normal
                    startLocationUpdates()
                    val serviceIntent = Intent(this, LocationService::class.java)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        startForegroundService(serviceIntent)
                    } else {
                        startService(serviceIntent)
                    }
                }
            } else {
                // If user selected "Don't ask again", show settings dialog
                if (!ActivityCompat.shouldShowRequestPermissionRationale(this, android.Manifest.permission.ACCESS_FINE_LOCATION)) {
                    showPermissionSettingsDialog()
                } else {
                    // Otherwise, just show a toast or rationale
                    Toast.makeText(this, "Location permission is required for this app to work.", Toast.LENGTH_LONG).show()
                }
            }
        } else if (requestCode == 1002) {
            // Background location permission result
            if (grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                // Background location granted, start location updates and service
                startLocationUpdates()
                val serviceIntent = Intent(this, LocationService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(serviceIntent)
                } else {
                    startService(serviceIntent)
                }
            } else {
                // If user selected "Don't ask again", show settings dialog
                if (!ActivityCompat.shouldShowRequestPermissionRationale(this, android.Manifest.permission.ACCESS_BACKGROUND_LOCATION)) {
                    showPermissionSettingsDialog()
                } else {
                    Toast.makeText(this, "Background location is required for full functionality.", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun showPermissionSettingsDialog() {
        AlertDialog.Builder(this)
            .setTitle("Permission Required")
            .setMessage("Location permission is required. Open app settings to grant permission?")
            .setPositiveButton("Open Settings") { _, _ ->
                val intent = Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                intent.data = Uri.fromParts("package", packageName, null)
                startActivity(intent)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // Helper to check if location is enabled
    private fun isLocationEnabled(): Boolean {
        val locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        return locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
               locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
    }

    // Helper to prompt user to enable location
    private fun promptEnableLocation() {
        AlertDialog.Builder(this)
            .setTitle("Enable Location")
            .setMessage("Location is turned off. Enable location to continue?")
            .setPositiveButton("Yes") { _, _ ->
                startActivity(Intent(android.provider.Settings.ACTION_LOCATION_SOURCE_SETTINGS))
            }
            .setNegativeButton("No", null)
            .show()
    }

    // Helper to prompt user to enable location permission
    private fun promptLocationPermission() {
        // Only request foreground location permission directly, no popup
        PermissionHelper.requestForegroundLocationPermission(this, 1001)
    }
}

class RouteViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
    val codeView: TextView = itemView.findViewById(R.id.routeCodeText)
    val startView: TextView = itemView.findViewById(R.id.routeStartText)
    val endView: TextView = itemView.findViewById(R.id.routeEndText)
} 