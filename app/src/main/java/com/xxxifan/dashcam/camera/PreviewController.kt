package com.xxxifan.dashcam.camera

import android.content.Context
import android.hardware.camera2.CaptureRequest
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.core.CameraInfo
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.xxxifan.dashcam.data.RecordingSettings
import com.xxxifan.dashcam.data.coerceCropZoomRatio

object PreviewController {
    private var boundPreview: Preview? = null
    private var generation = 0

    fun bind(
        context: Context,
        lifecycleOwner: LifecycleOwner,
        previewView: PreviewView,
        settings: RecordingSettings,
    ) {
        val myGeneration = ++generation
        val providerFuture = ProcessCameraProvider.getInstance(context)
        providerFuture.addListener(
            {
                if (generation != myGeneration) return@addListener
                val provider = providerFuture.get()
                val logicalCameraId = CameraSelectionId.logicalCameraId(settings.cameraId)
                val selector = if (logicalCameraId.isNotBlank()) {
                    CameraSelector.Builder()
                        .addCameraFilter { infos ->
                            infos.filter { info -> cameraIdOf(info) == logicalCameraId }
                        }
                        .build()
                } else {
                    CameraSelector.DEFAULT_BACK_CAMERA
                }
                runCatching {
                    provider.unbindAll()
                    val preview = buildPreview(previewView, settings, usePhysicalCamera = true)
                    provider.bindToLifecycle(lifecycleOwner, selector, preview)
                    boundPreview = preview
                }.onFailure {
                    runCatching {
                        provider.unbindAll()
                        val preview = buildPreview(previewView, settings, usePhysicalCamera = false)
                        provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview)
                        boundPreview = preview
                    }
                }
            },
            ContextCompat.getMainExecutor(context),
        )
    }

    fun unbind(context: Context) {
        runCatching {
            val preview = boundPreview ?: return@runCatching
            ProcessCameraProvider.getInstance(context).get().unbind(preview)
            boundPreview = null
        }
    }

    private fun cameraIdOf(info: CameraInfo): String =
        Camera2CameraInfo.from(info).cameraId

    private fun buildPreview(
        previewView: PreviewView,
        settings: RecordingSettings,
        usePhysicalCamera: Boolean,
    ): Preview {
        val builder = Preview.Builder()
            .setDynamicRange(settings.dynamicRange.toCameraXDynamicRange())
        val extender = Camera2Interop.Extender(builder)
        val physicalCameraId = CameraSelectionId.physicalCameraId(settings.cameraId)
        if (usePhysicalCamera && !physicalCameraId.isNullOrBlank()) {
            extender.setPhysicalCameraId(physicalCameraId)
        }
        extender.setCaptureRequestOption(
            CaptureRequest.CONTROL_ZOOM_RATIO,
            settings.cropZoomRatio.coerceCropZoomRatio(),
        )
        extender.applyFocusMode(settings.focusMode)
        return builder.build().also {
            it.surfaceProvider = previewView.surfaceProvider
        }
    }
}
