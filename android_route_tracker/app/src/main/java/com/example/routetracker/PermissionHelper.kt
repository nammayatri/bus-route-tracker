package com.example.routetracker

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.app.ActivityCompat

object PermissionHelper {
    val foregroundPermissions: List<String>
        get() = listOf(Manifest.permission.ACCESS_FINE_LOCATION)

    val backgroundPermissions: List<String>
        get() = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q)
            listOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        else
            emptyList()

    fun hasForegroundLocationPermission(context: Context): Boolean {
        return foregroundPermissions.all {
            ActivityCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    fun hasBackgroundLocationPermission(context: Context): Boolean {
        return backgroundPermissions.all {
            ActivityCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    fun requestForegroundLocationPermission(activity: Activity, requestCode: Int) {
        ActivityCompat.requestPermissions(activity, foregroundPermissions.toTypedArray(), requestCode)
    }

    fun requestBackgroundLocationPermission(activity: Activity, requestCode: Int) {
        if (backgroundPermissions.isNotEmpty()) {
            ActivityCompat.requestPermissions(activity, backgroundPermissions.toTypedArray(), requestCode)
        }
    }
} 