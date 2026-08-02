package com.wirefinder.ui.main

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.wirefinder.data.APNetwork
import com.wirefinder.databinding.ItemNetworkBinding
import com.wirefinder.util.NetworkMatcher
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class NetworkAdapter : ListAdapter<APNetwork, NetworkAdapter.NetworkViewHolder>(DiffCallback()) {

    var onNetworkClick: ((APNetwork) -> Unit)? = null
    var onShareClick: ((APNetwork) -> Unit)? = null

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): NetworkViewHolder {
        val binding = ItemNetworkBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return NetworkViewHolder(binding)
    }

    override fun onBindViewHolder(holder: NetworkViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class NetworkViewHolder(private val binding: ItemNetworkBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(network: APNetwork) {
            binding.apply {
                tvSsid.text = network.ssid
                tvMac.text = network.mac

                val dateFormat = SimpleDateFormat("MMM dd, HH:mm", Locale.getDefault())
                tvLastSeen.text = itemView.context.getString(
                    com.wirefinder.R.string.network_last_seen,
                    dateFormat.format(Date(network.lastSeen))
                )
                tvSignal.text = "${network.lastSignalStrength} dBm"
                tvSignal.setTextColor(
                    if (network.lastSignalStrength > -60)
                        itemView.context.getColor(com.wirefinder.R.color.online_green)
                    else if (network.lastSignalStrength > -75)
                        itemView.context.getColor(com.wirefinder.R.color.warning_orange)
                    else
                        itemView.context.getColor(com.wirefinder.R.color.offline_gray)
                )
                ivStatus.setImageResource(com.wirefinder.R.drawable.ic_wifi_connected)

                val matchType = NetworkMatcher.MatchType.fromDb(network.lastMatchType)
                llMatchBadges.visibility = if (matchType == null) View.GONE else View.VISIBLE
                val ssidMatched = matchType == NetworkMatcher.MatchType.SSID_ONLY ||
                    matchType == NetworkMatcher.MatchType.BOTH
                val macMatched = matchType == NetworkMatcher.MatchType.MAC_ONLY ||
                    matchType == NetworkMatcher.MatchType.BOTH
                tvBadgeSsid.visibility = if (ssidMatched) View.VISIBLE else View.GONE
                tvBadgeMac.visibility = if (macMatched) View.VISIBLE else View.GONE
                tvBadgeSsid.setTextColor(
                    if (matchType == NetworkMatcher.MatchType.BOTH) {
                        itemView.context.getColor(com.wirefinder.R.color.online_green)
                    } else {
                        itemView.context.getColor(com.wirefinder.R.color.warning_orange)
                    }
                )
                tvBadgeSsid.contentDescription = if (matchType == NetworkMatcher.MatchType.BOTH) {
                    itemView.context.getString(com.wirefinder.R.string.match_badge_both_desc)
                } else {
                    itemView.context.getString(com.wirefinder.R.string.match_badge_ssid_desc)
                }
                tvBadgeMac.contentDescription =
                    itemView.context.getString(com.wirefinder.R.string.match_badge_mac_desc)

                binding.root.setOnClickListener { onNetworkClick?.invoke(network) }
                btnShare.setOnClickListener { onShareClick?.invoke(network) }
            }
        }
    }

    class DiffCallback : DiffUtil.ItemCallback<APNetwork>() {
        override fun areItemsTheSame(oldItem: APNetwork, newItem: APNetwork): Boolean {
            return oldItem.mac == newItem.mac
        }

        override fun areContentsTheSame(oldItem: APNetwork, newItem: APNetwork): Boolean {
            return oldItem == newItem
        }
    }
}
