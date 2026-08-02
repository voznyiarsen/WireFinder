package com.wirefinder.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.wifi.ScanResult
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.content.pm.ServiceInfo
import com.wirefinder.R
import com.wirefinder.data.AppDatabase
import com.wirefinder.data.APNetworkDao
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import timber.log.Timber

@AndroidEntryPoint
class WifiScanForegroundService : Service() {

    private var wifiManager: WifiManager? = null
    private var powerManager: PowerManager? = null
    private var wakeLock: PowerManager.WakeLock? = null
    
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var scanJob: Job? = null
    private var passiveReceiver: WifiScanReceiver? = null
    private var networkMatcherRefreshJob: Job? = null

    @Inject lateinit var networkMatcher: NetworkMatcher
    @Inject lateinit var notificationService: NotificationService
    @Inject lateinit var preferencesManager: PreferencesManager

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

    override fun onCreate() {
        super.onCreate()
        wifiManager = getSystemService(Context.WIFI_SERVICE) as WifiManager
        powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        
        // Acquire wake lock for background scanning
        wakeLock = powerManager?.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "WireFinder::WifiScanWakeLock"
        )
        
        // Create foreground service notification channel
        createNotificationChannel()
        
        // Register passive scan receiver
        passiveReceiver = WifiScanReceiver(this)
        passiveReceiver?.register()
        
        // Load initial networks from database
        loadNetworksFromDatabase()
        
        // Start periodic network matcher refresh
        startNetworkMatcherRefresh()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
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
    }

    private fun loadNetworksFromDatabase() {
        scope.launch {
            val db = AppDatabase.getDatabase(this@WifiScanForegroundService)
            val networks = db.apNetworkDao().getAllNetworksOnce()
            if (networks.isNotEmpty()) {
                networkMatcher.updateKnownNetworks(networks)
                Timber.i("Loaded ${networks.size} networks into NetworkMatcher from database")
            }
        }
    }

    private fun startNetworkMatcherRefresh() {
        networkMatcherRefreshJob?.cancel()
        networkMatcherRefreshJob = scope.launch {
            while (isActive) {
                // Refresh every 5 minutes to pick up any database changes
                kotlinx.coroutines.delay(5 * 60 * 1000L)
                loadNetworksFromDatabase()
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = createForegroundNotification()
        startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        
        // Start periodic scanning
        startPeriodicScanning()
        
        return START_STICKY
    }

    private fun createForegroundNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = android.app.PendingIntent.getActivity(
            this, 0, intent,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )

        return androidx.core.app.NotificationCompat.Builder(this, FOREGROUND_SERVICE_CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.scanning_active))
            .setSmallIcon(R.drawable.ic_notification_wifi)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setCategory(androidx.core.app.NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun startPeriodicScanning() {
        scanJob?.cancel()
        scanJob = scope.launch {
            while (isActive) {
                val enabled = preferencesManager.scanningEnabled.first()
                if (enabled) {
                    val interval = preferencesManager.scanIntervalMinutes.first()
                    performScan()
                    val delayMs = interval * 60 * 1000L
                    kotlinx.coroutines.delay(delayMs)
                } else {
                    Timber.i("Scanning disabled in settings, stopping service")
                    stopSelf()
                    break
                }
            }
        }
    }

    internal fun performScan() {
        // ACCESS_FINE_LOCATION required for Wi-Fi scanning on all versions
        if (checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED) {
            Timber.w("Missing ACCESS_FINE_LOCATION permission for scanning")
            return
        }

        wakeLock?.acquire(10000)
        
        try {
            val success = wifiManager?.startScan() ?: false
            if (!success) {
                Timber.w("Wi-Fi scan request failed")
            } else {
                Timber.d("Wi-Fi scan initiated")
            }
        } catch (e: SecurityException) {
            Timber.e(e, "Security exception during Wi-Fi scan")
        } finally {
            wakeLock?.release()
        }
    }

    /**
     * Called by WifiScanReceiver when scan results are available.
     */
    fun onScanResults(results: List<ScanResult>) {
        Timber.d("Received ${results.size} scan results")
        
        for (result in results) {
            val matchedNetwork = networkMatcher.matchNetwork(
                result.BSSID,
                result.SSID.replace("\"", ""), // Remove quotes from SSID
                result.level,
                requireBssid = true
            )
            
            if (matchedNetwork != null) {
                val network = matchedNetwork.network
                Timber.i("Matched known network: ${network.ssid} (${network.mac}) at ${result.level} dBm (${matchedNetwork.type})")
                notificationService.showNetworkDetectedNotification(network, result.level)
                
                // Update last seen in database
                scope.launch {
                    val db = AppDatabase.getDatabase(this@WifiScanForegroundService)
                    db.apNetworkDao().updateDetection(network.mac, System.currentTimeMillis(), result.level, matchedNetwork.type.dbValue)
                }
            }
        }
    }

    override fun onDestroy() {
        scanJob?.cancel()
        networkMatcherRefreshJob?.cancel()
        passiveReceiver?.unregister()
        wakeLock?.release()
        scope.coroutineContext.cancelChildren()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}