package com.example.routetracker

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

data class RouteDisplayItem(
    val routeCode: String,
    val routeStart: String,
    val routeEnd: String
)

class RouteAdapter(
    private var routes: List<RouteDisplayItem>,
    private val onRouteClick: (RouteDisplayItem) -> Unit
) : RecyclerView.Adapter<RouteAdapter.RouteViewHolder>() {

    inner class RouteViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val routeText: TextView = itemView.findViewById(R.id.routeText)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RouteViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_route, parent, false)
        return RouteViewHolder(view)
    }

    override fun onBindViewHolder(holder: RouteViewHolder, position: Int) {
        val route = routes[position]
        holder.routeText.text = "${route.routeCode} | ${route.routeStart} -> ${route.routeEnd}"
        holder.itemView.setOnClickListener { onRouteClick(route) }
    }

    override fun getItemCount() = routes.size

    fun updateRoutes(newRoutes: List<RouteDisplayItem>) {
        routes = newRoutes
        notifyDataSetChanged()
    }
} 