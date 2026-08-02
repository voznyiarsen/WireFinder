package com.wirefinder

import android.app.Application
import android.content.Intent
import android.os.Process
import android.util.Log
import com.google.android.material.color.DynamicColors
import com.wirefinder.ui.crash.CrashLogActivity
import dagger.hilt.android.HiltAndroidApp
import timber.log.Timber
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@HiltAndroidApp
class WireFinderApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        DynamicColors.applyToActivitiesIfAvailable(this)
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        }
        installCrashHandler()
    }

    private fun installCrashHandler() {
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val logText = buildString {
                    append("WireFinder crash log\n")
                    append("Time: ")
                        .append(SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault()).format(Date()))
                        .append('\n')
                    append("Thread: ").append(thread.name).append('\n')
                    append("Process: ").append(Process.myPid()).append('\n')
                    append('\n')
                    append(Log.getStackTraceString(throwable))
                }
                File(filesDir, "crash_log.txt").writeText(logText)

                val intent = Intent(this, CrashLogActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                    putExtra(CrashLogActivity.EXTRA_LOG, logText)
                }
                startActivity(intent)
            } catch (_: Exception) {
            }
            // CrashLogActivity runs in its own process, so this is safe
            Process.killProcess(Process.myPid())
        }
    }
}
