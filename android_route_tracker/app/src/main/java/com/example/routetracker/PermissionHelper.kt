package com.example.routetracker

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.app.ActivityCompat

object PermissionHelper {
    private val permissions: List<String>
        get() {
            val perms = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                perms.add(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
            }
            if (android.os.Build.VERSION.SDK_INT >= 34) {
                perms.add(Manifest.permission.FOREGROUND_SERVICE_LOCATION)
            }
            return perms
        }

    fun hasAllLocationPermissions(context: Context): Boolean {
        return permissions.all {
            ActivityCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    fun requestLocationPermissions(activity: Activity, requestCode: Int) {
        ActivityCompat.requestPermissions(activity, permissions.toTypedArray(), requestCode)
    }
} 