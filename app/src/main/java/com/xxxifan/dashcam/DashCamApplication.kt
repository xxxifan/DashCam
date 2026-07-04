package com.xxxifan.dashcam

import android.app.Application
import com.tencent.mmkv.MMKV

class DashCamApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        MMKV.initialize(this)
    }
}
