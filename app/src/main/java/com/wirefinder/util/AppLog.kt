package com.wirefinder.util

import android.util.Log as AndroidLog
import com.wirefinder.BuildConfig

object AppLog {
    private val ENABLED = BuildConfig.DEBUG

    fun e(tag: String, msg: String) {
        if (ENABLED) AndroidLog.e(tag, msg)
    }

    fun e(tag: String, msg: String, tr: Throwable) {
        if (ENABLED) AndroidLog.e(tag, msg, tr)
    }

    fun w(tag: String, msg: String) {
        if (ENABLED) AndroidLog.w(tag, msg)
    }

    fun i(tag: String, msg: String) {
        if (ENABLED) AndroidLog.i(tag, msg)
    }

    fun getStackTraceString(e: Throwable): String = AndroidLog.getStackTraceString(e)
}
