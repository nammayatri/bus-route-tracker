package com.example.routetracker

import okhttp3.*
import java.io.IOException

object NetworkHelper {
    private val client = OkHttpClient()

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

    fun authenticatedRequest(url: String, method: String, sessionCookie: String, body: RequestBody? = null, onSuccess: (String) -> Unit, onError: (Exception) -> Unit) {
        val builder = Request.Builder().url(url).addHeader("Cookie", sessionCookie)
        if (method == "POST" && body != null) builder.post(body)
        val request = builder.build()
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
} 