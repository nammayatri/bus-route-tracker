package com.example.routetracker

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import org.json.JSONObject
import java.io.IOException
import com.example.routetracker.Constants
import com.example.routetracker.PermissionHelper
import com.example.routetracker.ToastHelper

class LoginActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_login)

        val passwordEditText = findViewById<EditText>(R.id.passwordEditText)
        val loginButton = findViewById<Button>(R.id.loginButton)

        loginButton.setOnClickListener {
            val password = passwordEditText.text.toString()
            if (password.isNotEmpty()) {
                login(password)
            } else {
                ToastHelper.show(this, "Enter password")
            }
        }

        // Check location permissions at startup
        checkLocationPermissions()
    }

    private fun checkLocationPermissions() {
        if (!PermissionHelper.hasAllLocationPermissions(this)) {
            PermissionHelper.requestLocationPermissions(this, 1002)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1002) {
            val fineLocationGranted = permissions.indexOf(android.Manifest.permission.ACCESS_FINE_LOCATION).let { idx ->
                idx != -1 && grantResults.getOrNull(idx) == PackageManager.PERMISSION_GRANTED
            }
            val backgroundLocationGranted = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                permissions.indexOf(android.Manifest.permission.ACCESS_BACKGROUND_LOCATION).let { idx ->
                    idx != -1 && grantResults.getOrNull(idx) == PackageManager.PERMISSION_GRANTED
                }
            } else true
            val foregroundServiceLocationGranted = if (android.os.Build.VERSION.SDK_INT >= 34) {
                permissions.indexOf(android.Manifest.permission.FOREGROUND_SERVICE_LOCATION).let { idx ->
                    idx != -1 && grantResults.getOrNull(idx) == PackageManager.PERMISSION_GRANTED
                }
            } else true
            if (fineLocationGranted && backgroundLocationGranted && foregroundServiceLocationGranted) {
                // All permissions granted, proceed
            } else {
                // Not all permissions granted, show a dialog to guide the user to settings
                AlertDialog.Builder(this)
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

    private fun login(password: String) {
        val url = "${Constants.BASE_URL}/routeTrackerApi/login"
        val json = JSONObject().apply { put("password", password) }
        val body = RequestBody.create("application/json".toMediaType(), json.toString())
        val request = Request.Builder().url(url).post(body).build()
        OkHttpClient().newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                runOnUiThread {
                    Toast.makeText(this@LoginActivity, "Network error", Toast.LENGTH_SHORT).show()
                }
            }
            override fun onResponse(call: Call, response: Response) {
                if (response.isSuccessful) {
                    // Get the session cookie from the response
                    val cookies = response.headers("Set-Cookie")
                    val sessionCookie = cookies.firstOrNull { it.startsWith("session=") }
                    val sessionValue = sessionCookie?.split(";")?.get(0) // Only 'session=...'
                    
                    if (sessionValue != null) {
                        // Store only the session=... part
                        getSharedPreferences("Auth", MODE_PRIVATE).edit().apply {
                            putString("session", sessionValue)
                            apply()
                        }
                        
                        startActivity(Intent(this@LoginActivity, MainActivity::class.java))
                        finish()
                    }
                }
            }
        })
    }
} 