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
        val codeText: TextView = itemView.findViewById(R.id.routeCodeText)
        val startText: TextView = itemView.findViewById(R.id.routeStartText)
        val endText: TextView = itemView.findViewById(R.id.routeEndText)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RouteViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_route, parent, false)
        return RouteViewHolder(view)
    }

    override fun onBindViewHolder(holder: RouteViewHolder, position: Int) {
        val route = routes[position]
        holder.codeText.text = route.routeCode
        holder.startText.text = route.routeStart
        holder.endText.text = route.routeEnd
        holder.itemView.setOnClickListener { onRouteClick(route) }
    }

    override fun getItemCount() = routes.size

    fun updateRoutes(newRoutes: List<RouteDisplayItem>) {
        routes = newRoutes
        notifyDataSetChanged()
    }
} 