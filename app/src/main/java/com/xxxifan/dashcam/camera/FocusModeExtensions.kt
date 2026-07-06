package com.xxxifan.dashcam.camera

import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import androidx.camera.camera2.interop.Camera2Interop
import com.xxxifan.dashcam.data.FocusMode

internal fun Camera2Interop.Extender<*>.applyFocusMode(focusMode: FocusMode) {
    runCatching {
        when (focusMode) {
            FocusMode.Farthest -> {
                setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF)
                setCaptureRequestOption(CaptureRequest.LENS_FOCUS_DISTANCE, 0.0f)
            }
            FocusMode.Auto -> {
                setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
            }
        }
    }
}
