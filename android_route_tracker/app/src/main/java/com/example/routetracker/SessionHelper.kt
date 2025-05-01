package com.example.routetracker

import android.app.Activity
import android.content.Context
import android.content.Intent

object SessionHelper {
    fun handleSessionExpired(activity: Activity) {
        ToastHelper.show(activity, "Session expired. Please login again.")
        activity.getSharedPreferences("Auth", Context.MODE_PRIVATE).edit().clear().apply()
        activity.startActivity(Intent(activity, LoginActivity::class.java))
        activity.finish()
    }
} 