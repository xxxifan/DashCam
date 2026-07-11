package com.xxxifan.dashcam

import android.app.Application
import com.tencent.mmkv.MMKV
import com.xxxifan.dashcam.device.remote.DeviceManager

class DashCamApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        MMKV.initialize(this)
        DeviceManager.get(this).startEntryProbe()
    }
}
