package com.wirefinder.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.WifiManager
import com.wirefinder.service.WifiScanForegroundService
import timber.log.Timber

class WifiScanReceiver(private val service: WifiScanForegroundService) : BroadcastReceiver() {

    private var registered = false

    fun register() {
        if (!registered) {
            val filter = IntentFilter().apply {
                addAction(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)
                addAction(WifiManager.WIFI_STATE_CHANGED_ACTION)
            }
            service.registerReceiver(this, filter, Context.RECEIVER_NOT_EXPORTED)
            registered = true
            Timber.d("WifiScanReceiver registered")
        }
    }

    fun unregister() {
        if (registered) {
            try {
                service.unregisterReceiver(this)
                registered = false
                Timber.d("WifiScanReceiver unregistered")
            } catch (e: IllegalArgumentException) {
                // Already unregistered
            }
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            WifiManager.SCAN_RESULTS_AVAILABLE_ACTION -> {
                service.onScanResultsAvailable()
            }
            WifiManager.WIFI_STATE_CHANGED_ACTION -> {
                val state = intent.getIntExtra(WifiManager.EXTRA_WIFI_STATE, WifiManager.WIFI_STATE_UNKNOWN)
                Timber.d("Wi-Fi state changed: $state")
                if (state == WifiManager.WIFI_STATE_ENABLED) {
                    // Wi-Fi turned on, trigger a scan
                    service.performScan()
                }
            }
        }
    }
}