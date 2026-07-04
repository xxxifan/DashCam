package com.xxxifan.dashcam.camera

import android.content.Context
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

object PreviewController {
    fun bind(
        context: Context,
        lifecycleOwner: LifecycleOwner,
        previewView: PreviewView,
        settings: RecordingSettings,
    ) {
        val providerFuture = ProcessCameraProvider.getInstance(context)
        providerFuture.addListener(
            {
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
                    provider.bindToLifecycle(
                        lifecycleOwner,
                        selector,
                        buildPreview(previewView, settings, usePhysicalCamera = true),
                    )
                }.onFailure {
                    provider.unbindAll()
                    provider.bindToLifecycle(
                        lifecycleOwner,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        buildPreview(previewView, settings, usePhysicalCamera = false),
                    )
                }
            },
            ContextCompat.getMainExecutor(context),
        )
    }

    fun unbind(context: Context) {
        runCatching {
            ProcessCameraProvider.getInstance(context).get().unbindAll()
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
        return builder.build().also {
            it.surfaceProvider = previewView.surfaceProvider
        }
    }
}
