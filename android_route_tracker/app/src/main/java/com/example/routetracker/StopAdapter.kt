package com.example.routetracker

import android.graphics.Typeface
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView

class StopAdapter(
    private var stops: List<StopDisplayItem>,
    private val onStopClick: (StopDisplayItem) -> Unit
) : RecyclerView.Adapter<StopAdapter.StopViewHolder>() {

    var selectedPosition: Int = RecyclerView.NO_POSITION
    val currentList: List<StopDisplayItem> get() = stops

    inner class StopViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val stopNameText: TextView = itemView.findViewById(R.id.stopNameText)
        val stopDistanceText: TextView = itemView.findViewById(R.id.stopDistanceText)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): StopViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_stop, parent, false)
        return StopViewHolder(view)
    }

    override fun onBindViewHolder(holder: StopViewHolder, position: Int) {
        val stop = stops[position]
        holder.stopNameText.text = stop.stopName
        holder.stopDistanceText.text = String.format("%.2f km", stop.distanceMeters / 1000.0)
        if (stop.isTop3) {
            holder.itemView.setBackgroundColor(ContextCompat.getColor(holder.itemView.context, R.color.highlight))
            holder.stopNameText.setTypeface(null, Typeface.BOLD)
        } else {
            holder.itemView.setBackgroundColor(ContextCompat.getColor(holder.itemView.context, android.R.color.transparent))
            holder.stopNameText.setTypeface(null, Typeface.NORMAL)
        }
        // Highlight if selected
        if (position == selectedPosition) {
            holder.itemView.background = ContextCompat.getDrawable(holder.itemView.context, R.drawable.selected_stop_background)
        } else {
            holder.itemView.background = null
        }
        holder.itemView.setOnClickListener {
            val previousPosition = selectedPosition
            selectedPosition = position
            notifyItemChanged(previousPosition)
            notifyItemChanged(position)
            onStopClick(stop)
        }
    }

    override fun getItemCount() = stops.size

    fun updateStops(newStops: List<StopDisplayItem>) {
        stops = newStops
        notifyDataSetChanged()
    }
} 