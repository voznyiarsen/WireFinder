package com.wirefinder.ui.main

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.wirefinder.R
import com.wirefinder.data.APNetwork
import com.wirefinder.databinding.ItemNetworkBinding
import com.wirefinder.util.NetworkMatcher
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class NetworkAdapter : ListAdapter<APNetwork, NetworkAdapter.NetworkViewHolder>(DiffCallback()) {

    var onNetworkClick: ((APNetwork) -> Unit)? = null
    var onShareClick: ((APNetwork) -> Unit)? = null

    private val dateFormat = SimpleDateFormat("MMM dd, HH:mm", Locale.getDefault())

    init {
        setHasStableIds(true)
    }

    override fun getItemId(position: Int): Long {
        val mac = getItem(position).mac
        return mac.filter { it != ':' }.toLongOrNull(16)
            ?: mac.hashCode().toLong()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): NetworkViewHolder {
        val binding = ItemNetworkBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return NetworkViewHolder(binding)
    }

    override fun onBindViewHolder(holder: NetworkViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class NetworkViewHolder(private val binding: ItemNetworkBinding) :
        RecyclerView.ViewHolder(binding.root) {

        private val context = binding.root.context
        private val onlineGreen = context.getColor(R.color.online_green)
        private val warningOrange = context.getColor(R.color.warning_orange)
        private val offlineGray = context.getColor(R.color.offline_gray)
        private val ssidBadgeDesc = context.getString(R.string.match_badge_ssid_desc)
        private val bothBadgeDesc = context.getString(R.string.match_badge_both_desc)
        private val macBadgeDesc = context.getString(R.string.match_badge_mac_desc)

        init {
            binding.ivStatus.setImageResource(R.drawable.ic_wifi_connected)
            binding.root.setOnClickListener {
                val position = absoluteAdapterPosition
                if (position != RecyclerView.NO_POSITION) {
                    onNetworkClick?.invoke(getItem(position))
                }
            }
            binding.btnShare.setOnClickListener {
                val position = absoluteAdapterPosition
                if (position != RecyclerView.NO_POSITION) {
                    onShareClick?.invoke(getItem(position))
                }
            }
        }

        fun bind(network: APNetwork) {
            binding.apply {
                tvSsid.text = network.ssid
                tvMac.text = network.mac
                tvLastSeen.text = context.getString(
                    R.string.network_last_seen,
                    dateFormat.format(Date(network.lastSeen))
                )
                tvSignal.text = "${network.lastSignalStrength} dBm"
                tvSignal.setTextColor(
                    when {
                        network.lastSignalStrength > -60 -> onlineGreen
                        network.lastSignalStrength > -75 -> warningOrange
                        else -> offlineGray
                    }
                )

                val matchType = NetworkMatcher.MatchType.fromDb(network.lastMatchType)
                llMatchBadges.visibility = if (matchType == null) View.GONE else View.VISIBLE
                val ssidMatched = matchType == NetworkMatcher.MatchType.SSID_ONLY ||
                    matchType == NetworkMatcher.MatchType.BOTH
                val macMatched = matchType == NetworkMatcher.MatchType.MAC_ONLY ||
                    matchType == NetworkMatcher.MatchType.BOTH
                tvBadgeSsid.visibility = if (ssidMatched) View.VISIBLE else View.GONE
                tvBadgeMac.visibility = if (macMatched) View.VISIBLE else View.GONE
                tvBadgeSsid.setTextColor(
                    if (matchType == NetworkMatcher.MatchType.BOTH) onlineGreen else warningOrange
                )
                tvBadgeSsid.contentDescription =
                    if (matchType == NetworkMatcher.MatchType.BOTH) bothBadgeDesc else ssidBadgeDesc
                tvBadgeMac.contentDescription = macBadgeDesc
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
