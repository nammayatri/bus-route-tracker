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
import android.view.ViewGroup
import android.view.WindowManager
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.widget.Button
import android.widget.ListView
import com.google.android.material.bottomsheet.BottomSheetDialog
import android.widget.AutoCompleteTextView
import android.view.inputmethod.InputMethodManager
import android.provider.Settings

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

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
                if (PermissionHelper.hasAllLocationPermissions(this)) {
                    val serviceIntent = Intent(this, LocationService::class.java)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        startForegroundService(serviceIntent)
                    } else {
                        startService(serviceIntent)
                    }
                } else {
                    PermissionHelper.requestLocationPermissions(this, 1001)
                }
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
        // Set nearest stop distance
        if (stopItems.isNotEmpty()) {
            val nearest = stopItems[0]
            val distStr = if (nearest.distanceMeters >= 1000) {
                String.format("%.2f km", nearest.distanceMeters / 1000.0)
            } else {
                String.format("%.0f m", nearest.distanceMeters)
            }
            // nearestStopDistanceView.text = "Nearest stop: $distStr away"
        } else {
            nearestStopDistanceView.text = "No stops available"
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
            (sheetView as LinearLayout).addView(noResultsText)
            sheetView.addView(manualEntryEdit)
            sheetView.addView(manualEntryButton)

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
            Log.d("MainActivity", "Logging stop with payload: $json")
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
        (sheetView as ViewGroup).addView(loadingText)
        (sheetView as ViewGroup).addView(retryButton)

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

        val sortedRoutes = routes.sortedWith(compareBy {
            it.optString("route_code", "").toIntOrNull() ?: Int.MAX_VALUE
        })
        val routeItems = sortedRoutes.map {
            val code = it.optString("route_code", "")
            val start = it.optString("route_start_point", "")
            val end = it.optString("route_end_point", "")
            Triple(code, start, end)
        }
        var filtered = routeItems
        val adapter = object : RecyclerView.Adapter<RouteViewHolder>() {
            var items = filtered
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RouteViewHolder {
                val v = LayoutInflater.from(parent.context).inflate(R.layout.item_route, parent, false)
                return RouteViewHolder(v)
            }
            override fun onBindViewHolder(holder: RouteViewHolder, position: Int) {
                val (code, start, end) = items[position]
                holder.codeView.text = code
                holder.startView.text = start
                holder.endView.text = end
                holder.itemView.setOnClickListener {
                    selectedRouteId = code
                    selectedRouteDisplay = "$code | $start -> $end"
                    routeSelectButton.text = selectedRouteDisplay
                    if (selectedRouteId != null) fetchStops(selectedRouteId!!)
                    dialog.dismiss()
                }
            }
            override fun getItemCount() = items.size
        }
        recyclerView.adapter = adapter
        searchEdit.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {}
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val query = s.toString()
                val isInt = query.toIntOrNull() != null
                val filteredList = routeItems.filter {
                    it.first.contains(query, true) || it.second.contains(query, true) || it.third.contains(query, true)
                }
                val sorted = if (isInt) {
                    filteredList.sortedBy { it.first.toIntOrNull() ?: Int.MAX_VALUE }
                } else {
                    filteredList.sortedBy { it.first }
                }
                adapter.items = sorted
                adapter.notifyDataSetChanged()
            }
        })
        dialog.setContentView(sheetView)
        dialog.show()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1001 && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
            val serviceIntent = Intent(this, LocationService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
        } else if (requestCode == 1001) {
            Toast.makeText(this, "Location permission is required for location updates.", Toast.LENGTH_LONG).show()
        }
    }
}

class RouteViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
    val codeView: TextView = itemView.findViewById(R.id.routeCodeText)
    val startView: TextView = itemView.findViewById(R.id.routeStartText)
    val endView: TextView = itemView.findViewById(R.id.routeEndText)
} 