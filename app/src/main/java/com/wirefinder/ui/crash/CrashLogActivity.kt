package com.wirefinder.ui.crash

import android.app.Activity
import android.os.Bundle
import android.os.Process
import android.widget.TextView
import com.wirefinder.R
import com.wirefinder.util.ErrorDialog

/**
 * Shows a copyable crash log after an uncaught exception.
 * Runs in its own process (":crash") so it survives the crash of the main process.
 */
class CrashLogActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_crash_log)

        val logText = intent?.getStringExtra(EXTRA_LOG) ?: getString(R.string.app_error_title)
        findViewById<TextView>(R.id.crash_log_details).text = logText

        findViewById<com.google.android.material.button.MaterialButton>(R.id.btn_copy_log)
            .setOnClickListener { ErrorDialog.copyToClipboard(this, "crash_log", logText) }

        findViewById<com.google.android.material.button.MaterialButton>(R.id.btn_close_log)
            .setOnClickListener {
                finishAndRemoveTask()
                Process.killProcess(Process.myPid())
            }
    }

    companion object {
        const val EXTRA_LOG = "log"
    }
}
