package com.example.routetracker

data class RouteDisplayItem(
    val routeCode: String,
    val routeStart: String,
    val routeEnd: String,
    val routeNumber: String? = null,
    val naturalSortKey: List<Comparable<*>> = emptyList()
) 