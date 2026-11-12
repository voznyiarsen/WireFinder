package com.wirefinder.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.wifi.ScanResult
import android.net.wifi.WifiManager
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.wirefinder.R
import com.wirefinder.data.APNetwork
import com.wirefinder.data.DetectionUpdate
import com.wirefinder.data.KnownNetworkRepository
import com.wirefinder.receiver.WifiScanReceiver
import com.wirefinder.ui.main.MainActivity
import com.wirefinder.util.NetworkMatcher
import com.wirefinder.util.NotificationService
import com.wirefinder.util.PreferencesManager
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import timber.log.Timber

@AndroidEntryPoint
class WifiScanForegroundService : Service() {

    private var wifiManager: WifiManager? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var scanJob: Job? = null
    private var passiveReceiver: WifiScanReceiver? = null

    @Inject lateinit var networkMatcher: NetworkMatcher
    @Inject lateinit var notificationService: NotificationService
    @Inject lateinit var preferencesManager: PreferencesManager
    @Inject lateinit var knownNetworkRepository: KnownNetworkRepository

    companion object {
        const val FOREGROUND_SERVICE_CHANNEL_ID = "wifi_scan_service"

        fun createStartIntent(context: Context): Intent {
            return Intent(context, WifiScanForegroundService::class.java)
        }

        fun start(context: Context) {
            context.startForegroundService(createStartIntent(context))
        }

        fun stop(context: Context) {
            context.stopService(createStartIntent(context))
        }
    }

    private val foregroundNotification: Notification by lazy { createForegroundNotification() }

    override fun onCreate() {
        super.onCreate()
        wifiManager = getSystemService(Context.WIFI_SERVICE) as WifiManager
        createNotificationChannel()

        passiveReceiver = WifiScanReceiver(this).also { it.register() }

        scope.launch {
            knownNetworkRepository.reload()
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            FOREGROUND_SERVICE_CHANNEL_ID,
            getString(R.string.foreground_service_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.foreground_service_channel_desc)
        }
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(channel)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(1, foregroundNotification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        startPeriodicScanning()
        return START_STICKY
    }

    private fun createForegroundNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, FOREGROUND_SERVICE_CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.scanning_active))
            .setSmallIcon(R.drawable.ic_notification_wifi)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun startPeriodicScanning() {
        if (scanJob?.isActive == true) return
        scanJob = scope.launch {
            while (isActive) {
                if (!preferencesManager.scanningEnabled.first()) {
                    Timber.i("Scanning disabled in settings, stopping service")
                    stopSelf()
                    break
                }
                val interval = preferencesManager.scanIntervalMinutes.first()
                requestScan()
                delay(interval * 60 * 1000L)
            }
        }
    }

    internal fun performScan() {
        scope.launch { requestScan() }
    }

    private suspend fun requestScan() {
        if (checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            Timber.w("Missing ACCESS_FINE_LOCATION permission for scanning")
            return
        }

        try {
            val success = wifiManager?.startScan() ?: false
            if (!success) {
                Timber.w("Wi-Fi scan request failed")
            } else {
                Timber.d("Wi-Fi scan initiated")
            }
        } catch (e: SecurityException) {
            Timber.e(e, "Security exception during Wi-Fi scan")
        }
    }

    fun onScanResultsAvailable() {
        scope.launch {
            if (checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                return@launch
            }
            val results = wifiManager?.scanResults ?: return@launch
            if (results.isNotEmpty()) {
                processScanResults(results)
            }
        }
    }

    private suspend fun processScanResults(results: List<ScanResult>) {
        val bestByBssid = HashMap<String, ScanResult>(results.size)
        for (result in results) {
            val bssid = APNetwork.normalizeMac(result.BSSID)
            if (bssid.isEmpty() || bssid == "00:00:00:00:00:00") continue
            val existing = bestByBssid[bssid]
            if (existing == null || result.level > existing.level) {
                bestByBssid[bssid] = result
            }
        }
        if (bestByBssid.isEmpty()) return

        val now = System.currentTimeMillis()
        val updates = ArrayList<DetectionUpdate>(bestByBssid.size)
        val matches = ArrayList<Pair<NetworkMatcher.MatchResult, Int>>(bestByBssid.size)

        for (result in bestByBssid.values) {
            val matched = networkMatcher.matchNetwork(
                result.BSSID,
                result.SSID.replace("\"", ""),
                result.level,
                requireBssid = true
            ) ?: continue
            matches.add(matched to result.level)
            updates.add(
                DetectionUpdate(
                    mac = matched.network.mac,
                    lastSeen = now,
                    signal = result.level,
                    matchType = matched.type.dbValue
                )
            )
        }

        knownNetworkRepository.recordDetections(updates)

        for ((match, level) in matches) {
            val network = match.network
            Timber.i(
                "Matched known network: ${network.ssid} (${network.mac}) " +
                    "(${match.type}) at $level dBm"
            )
            notificationService.showNetworkDetectedNotification(network, level)
        }
    }

    override fun onDestroy() {
        scanJob?.cancel()
        passiveReceiver?.unregister()
        scope.coroutineContext.cancelChildren()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
