package com.magicearth.navigation.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.magicearth.navigation.databinding.ItemPoiResultBinding
import com.magicearth.navigation.map.PoiRepository
import com.magicearth.navigation.model.POI

class PoiAdapter(
    private var poiList: List<POI>,
    private val onFavoriteToggled: ((POI, Boolean) -> Unit)? = null,
    private val onPoiClicked: (POI) -> Unit
) : RecyclerView.Adapter<PoiAdapter.PoiViewHolder>() {

    class PoiViewHolder(val binding: ItemPoiResultBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PoiViewHolder {
        val binding = ItemPoiResultBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return PoiViewHolder(binding)
    }

    override fun onBindViewHolder(holder: PoiViewHolder, position: Int) {
        val poi = poiList[position]
        holder.binding.tvPoiName.text = poi.name
        holder.binding.tvPoiAddress.text = poi.address
        holder.binding.tvPoiDistance.text = poi.distanceText

        val icon = when (poi.category) {
            "fuel" -> "⛽"
            "ev" -> "⚡"
            "parking" -> "🅿️"
            "sight" -> "🏛️"
            "food" -> "🍽️"
            "saved" -> "⭐"
            else -> "📍"
        }
        holder.binding.tvPoiIcon.text = icon

        // Check if item is favorite in persistent storage
        val context = holder.itemView.context
        val isFav = PoiRepository.isFavorite(context, poi)
        if (isFav) {
            holder.binding.btnItemFavorite.setImageResource(android.R.drawable.btn_star_big_on)
        } else {
            holder.binding.btnItemFavorite.setImageResource(android.R.drawable.btn_star_big_off)
        }

        holder.binding.btnItemFavorite.setOnClickListener {
            val nowSaved = PoiRepository.toggleFavorite(context, poi)
            if (nowSaved) {
                holder.binding.btnItemFavorite.setImageResource(android.R.drawable.btn_star_big_on)
            } else {
                holder.binding.btnItemFavorite.setImageResource(android.R.drawable.btn_star_big_off)
            }
            onFavoriteToggled?.invoke(poi, nowSaved)
        }

        holder.itemView.setOnClickListener {
            onPoiClicked(poi)
        }
    }

    override fun getItemCount(): Int = poiList.size

    fun updateList(newList: List<POI>) {
        poiList = newList
        notifyDataSetChanged()
    }
}
