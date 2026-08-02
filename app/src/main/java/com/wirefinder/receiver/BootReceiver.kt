package com.wirefinder.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import com.wirefinder.service.WifiScanForegroundService
import com.wirefinder.util.PreferencesManager
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import timber.log.Timber

@AndroidEntryPoint
class BootReceiver : BroadcastReceiver() {

    @Inject
    lateinit var preferencesManager: PreferencesManager

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        val pendingResult = goAsync()
        scope.launch {
            try {
                val enabled = preferencesManager.scanningEnabled.first()
                if (enabled) {
                    Timber.d("Boot completed, restarting scanning service")
                    WifiScanForegroundService.start(context)
                } else {
                    Timber.d("Boot completed, scanning disabled, not starting service")
                }
            } finally {
                pendingResult.finish()
            }
        }
    }
}
