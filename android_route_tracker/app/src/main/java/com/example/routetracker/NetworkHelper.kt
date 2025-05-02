package com.example.routetracker

import okhttp3.*
import android.util.Log
import java.io.IOException

object NetworkHelper {
    val client = OkHttpClient.Builder()
        .addInterceptor { chain ->
            val original = chain.request()
            val requestBuilder = original.newBuilder()
            // Always add the Session-Api-Token header
            requestBuilder.addHeader("Session-Api-Token", Constants.SESSION_API_TOKEN)
            val request = requestBuilder.build()
            chain.proceed(request)
        }
        .hostnameVerifier { _, _ -> true } // Disable hostname verification (INSECURE, for testing only)
        .build()

    fun get(url: String, headers: Map<String, String> = emptyMap(), onSuccess: (String) -> Unit, onError: (Exception) -> Unit) {
        val requestBuilder = Request.Builder().url(url)
        headers.forEach { (k, v) -> requestBuilder.addHeader(k, v) }
        val request = requestBuilder.build()
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) = onError(e)
            override fun onResponse(call: Call, response: Response) {
                if (response.isSuccessful) {
                    onSuccess(response.body?.string() ?: "")
                } else {
                    onError(IOException("HTTP ${response.code}"))
                }
            }
        })
    }

    fun post(url: String, body: RequestBody, headers: Map<String, String> = emptyMap(), onSuccess: (String) -> Unit, onError: (Exception) -> Unit) {
        val requestBuilder = Request.Builder().url(url).post(body)
        headers.forEach { (k, v) -> requestBuilder.addHeader(k, v) }
        val request = requestBuilder.build()
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) = onError(e)
            override fun onResponse(call: Call, response: Response) {
                if (response.isSuccessful) {
                    onSuccess(response.body?.string() ?: "")
                } else {
                    onError(IOException("HTTP ${response.code}"))
                }
            }
        })
    }

    fun authenticatedRequest(url: String, method: String, body: RequestBody? = null, onSuccess: (String) -> Unit, onError: (Exception) -> Unit) {
        val builder = Request.Builder().url(url)
        if (method == "POST" && body != null) builder.post(body)
        val request = builder.build()
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) = onError(e)
            override fun onResponse(call: Call, response: Response) {
                if (response.isSuccessful) {
                    onSuccess(response.body?.string() ?: "")
                } else {
                    onError(IOException("HTTP \\${response.code}"))
                }
            }
        })
    }
} 