package com.xxxifan.dashcam.logging

import android.util.Log
import com.xxxifan.dashcam.BuildConfig

object ReleaseSafeLog {
    fun d(
        tag: String,
        message: String,
    ) {
        if (BuildConfig.DEBUG) {
            Log.d(tag, message)
        }
    }

    fun w(
        tag: String,
        message: String,
    ) {
        if (BuildConfig.DEBUG) {
            Log.w(tag, message)
        }
    }

    fun w(
        tag: String,
        message: String,
        error: Throwable,
    ) {
        if (BuildConfig.DEBUG) {
            Log.w(tag, message, error)
        }
    }
}
