package com.xxxifan.dashcam.device.remote

import android.content.Context
import android.media.MediaPlayer
import android.view.View
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.shuyu.gsyvideoplayer.GSYVideoManager
import com.shuyu.gsyvideoplayer.builder.GSYVideoOptionBuilder
import com.shuyu.gsyvideoplayer.listener.GSYSampleCallBack
import com.shuyu.gsyvideoplayer.model.VideoOptionModel
import com.shuyu.gsyvideoplayer.player.IjkPlayerManager
import com.shuyu.gsyvideoplayer.player.PlayerFactory
import com.shuyu.gsyvideoplayer.video.StandardGSYVideoPlayer
import tv.danmaku.ijk.media.player.IjkMediaPlayer
import java.io.IOException

@Composable
internal fun DevicePlayer(
    source: DevicePlaybackSource,
    showControls: Boolean,
    onError: (Throwable) -> Unit,
    onDiagnostic: (String, Map<String, Any?>) -> Unit,
    modifier: Modifier = Modifier
        .fillMaxWidth()
        .height(220.dp),
) {
    key(source.uri) {
        val playerHolder = remember { arrayOfNulls<DiagnosticGSYVideoPlayer>(1) }
        val options = remember(source) { source.ijkOptions() }

        DisposableEffect(Unit) {
            onDispose {
                playerHolder[0]?.release()
                playerHolder[0] = null
                onDiagnostic("device_ijk_player_released", emptyMap())
            }
        }

        Box(
            modifier = modifier,
            contentAlignment = Alignment.Center,
        ) {
            AndroidView(
                factory = { context ->
                    PlayerFactory.setPlayManager(IjkPlayerManager::class.java)
                    GSYVideoManager.instance().setOptionModelList(options)
                    onDiagnostic(
                        "device_ijk_player_created",
                        mapOf(
                            "kernel" to "IJK/FFmpeg software decoder",
                            "options" to source.ijkOptionDescription(),
                        ),
                    )
                    DiagnosticGSYVideoPlayer(context).also { player ->
                        player.onDiagnostic = onDiagnostic
                        playerHolder[0] = player
                        GSYVideoOptionBuilder()
                            .setUrl(source.uri.toString())
                            .setCacheWithPlay(false)
                            .setIsTouchWiget(showControls)
                            .setIsTouchWigetFull(showControls)
                            .setStartAfterPrepared(true)
                            .setShowPauseCover(false)
                            .setVideoAllCallBack(
                                object : GSYSampleCallBack() {
                                    override fun onStartPrepared(url: String?, vararg objects: Any?) {
                                        onDiagnostic("device_ijk_playback_preparing", emptyMap())
                                    }

                                    override fun onPrepared(url: String?, vararg objects: Any?) {
                                        onDiagnostic(
                                            "device_ijk_playback_prepared",
                                            player.diagnosticState(),
                                        )
                                    }

                                    override fun onAutoComplete(url: String?, vararg objects: Any?) {
                                        onDiagnostic(
                                            "device_ijk_playback_completed",
                                            player.diagnosticState(),
                                        )
                                    }

                                    override fun onPlayError(url: String?, vararg objects: Any?) {
                                        val codes = objects.takeLast(2).joinToString(",")
                                        val error = IOException("IJK/FFmpeg 播放失败：$codes")
                                        onDiagnostic(
                                            "device_ijk_playback_failed",
                                            player.diagnosticState() + ("codes" to codes),
                                        )
                                        onError(error)
                                    }
                                },
                            )
                            .build(player)
                        if (!showControls) {
                            player.titleTextView.visibility = View.GONE
                            player.backButton.visibility = View.GONE
                            player.fullscreenButton.visibility = View.GONE
                        }
                        player.startPlayLogic()
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

private class DiagnosticGSYVideoPlayer(
    context: Context,
) : StandardGSYVideoPlayer(context) {
    var onDiagnostic: (String, Map<String, Any?>) -> Unit = { _, _ -> }

    override fun onInfo(what: Int, extra: Int) {
        super.onInfo(what, extra)
        onDiagnostic(
            if (what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) {
                "device_ijk_first_frame_rendered"
            } else {
                "device_ijk_playback_info"
            },
            diagnosticState() + mapOf("what" to what, "extra" to extra),
        )
    }

    override fun onVideoSizeChanged() {
        super.onVideoSizeChanged()
        onDiagnostic("device_ijk_video_size_changed", diagnosticState())
    }

    override fun onError(what: Int, extra: Int) {
        onDiagnostic(
            "device_ijk_decoder_error",
            diagnosticState() + mapOf("what" to what, "extra" to extra),
        )
        super.onError(what, extra)
    }
}

private fun StandardGSYVideoPlayer.diagnosticState(): Map<String, Any?> = mapOf(
    "state" to currentState,
    "positionMillis" to currentPositionWhenPlaying,
    "durationMillis" to duration,
    "videoWidth" to currentVideoWidth,
    "videoHeight" to currentVideoHeight,
)

private fun DevicePlaybackSource.ijkOptions(): List<VideoOptionModel> = when (this) {
    is DevicePlaybackSource.Rtsp -> listOf(
        playerOption("mediacodec", 0),
        playerOption("mediacodec-auto-rotate", 1),
        playerOption("opensles", 0),
        playerOption("audio-resample", 1),
        formatOption("rtsp_transport", "tcp"),
        formatOption("rtsp_flags", "prefer_tcp"),
        formatOption("probesize", 0x40000),
        formatOption("analyzeduration", 500_000),
        formatOption("fflags", "nofillin"),
        playerOption("start-on-prepared", 1),
        playerOption("packet-buffering", 0),
        playerOption("framedrop", 5),
        playerOption("max-buffer-duration", 3_000),
        formatOption("flush_packets", 1),
        playerOption("enable-accurate-seek", 0),
        playerOption("overlay-format", 0x32335652),
    )

    is DevicePlaybackSource.Http -> listOf(
        playerOption("mediacodec", 0),
        playerOption("enable-accurate-seek", 1),
        formatOption("probesize", 0x1400000),
        playerOption("max-buffer-duration", 8_000),
        playerOption("min-frames", 50),
        playerOption("start-on-prepared", 1),
    )
}

private fun DevicePlaybackSource.ijkOptionDescription(): String = when (this) {
    is DevicePlaybackSource.Rtsp ->
        "software-decode,tcp,prefer_tcp,probesize=262144,analyzeduration=500000,nofillin,nobuffer"

    is DevicePlaybackSource.Http ->
        "software-decode,probesize=20971520,max-buffer-duration=8000,min-frames=50"
}

private fun playerOption(name: String, value: Int): VideoOptionModel =
    VideoOptionModel(IjkMediaPlayer.OPT_CATEGORY_PLAYER, name, value)

private fun formatOption(name: String, value: Int): VideoOptionModel =
    VideoOptionModel(IjkMediaPlayer.OPT_CATEGORY_FORMAT, name, value)

private fun formatOption(name: String, value: String): VideoOptionModel =
    VideoOptionModel(IjkMediaPlayer.OPT_CATEGORY_FORMAT, name, value)
