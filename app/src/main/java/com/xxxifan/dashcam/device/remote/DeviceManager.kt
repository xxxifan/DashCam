package com.xxxifan.dashcam.device.remote

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Environment
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

class DeviceManager private constructor(
    context: Context,
    private val drivers: List<DeviceProtocolDriver>,
    private val newSdkProbe: NewDeviceSdkProbe,
) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val connectivityManager = appContext.getSystemService(ConnectivityManager::class.java)
    private val networkResolver = DeviceNetworkResolver(appContext)
    private val diagnostics = DeviceDiagnosticsLogger(appContext)
    private val probeMutex = Mutex()
    private val sessionMutex = Mutex()
    private var activeSession: DeviceSession? = null
    private var activeRoute: DeviceNetworkRoute? = null
    private var networkCallbackRegistered = false
    private var lastObservedWifiNetwork: Network? = null

    private val _state = MutableStateFlow(
        DeviceUiState(
            diagnosticFile = diagnostics.currentFile(),
        ),
    )
    val state: StateFlow<DeviceUiState> = _state.asStateFlow()

    init {
        scope.launch {
            diagnostics.records.collectLatest { records ->
                _state.update {
                    it.copy(
                        diagnostics = records,
                        diagnosticFile = diagnostics.currentFile(),
                    )
                }
            }
        }
    }

    fun startEntryProbe() {
        registerWifiObserver()
        scope.launch {
            probeAll(trigger = "application_entry")
        }
    }

    suspend fun probeAll(trigger: String = "manual") {
        probeMutex.withLock {
            _state.update {
                it.copy(
                    isProbing = true,
                    probeTrigger = trigger,
                    statusMessage = "正在探测记录仪…",
                )
            }
            val route = networkResolver.findWifiRoute()
            diagnostics.log(
                "device_probe_run_started",
                mapOf(
                    "trigger" to trigger,
                    "wifiRoute" to (route?.description ?: "unavailable"),
                    "driverCount" to drivers.size,
                ),
            )
            val sdkSupport = runCatching { newSdkProbe.probe(route) }
                .getOrElse { error ->
                    NewSdkSupportStatus(
                        integrationAvailable = newSdkProbe.integrationAvailable,
                        deviceSupport = DeviceProbeStatus.Unreachable,
                        detail = error.message ?: error.javaClass.simpleName,
                    )
                }
            diagnostics.log(
                "new_sdk_support_probe",
                mapOf(
                    "integrationAvailable" to sdkSupport.integrationAvailable,
                    "deviceSupport" to sdkSupport.deviceSupport.name,
                    "detail" to sdkSupport.detail,
                ),
            )
            val results = if (route == null) {
                drivers.map { driver ->
                    DeviceProbeResult(
                        device = driver.definition,
                        status = DeviceProbeStatus.Unreachable,
                        detail = "当前没有可用于局域网探测的 Wi-Fi 网络",
                    )
                }
            } else {
                coroutineScope {
                    drivers.map { driver ->
                        async {
                            runCatching { driver.probe(route) }
                                .getOrElse { error ->
                                    DeviceProbeResult(
                                        device = driver.definition,
                                        status = DeviceProbeStatus.Unreachable,
                                        detail = error.message ?: error.javaClass.simpleName,
                                    )
                                }
                        }
                    }.map { it.await() }
                }
            }
            results.forEach { result ->
                diagnostics.log(
                    "device_probe_result",
                    mapOf(
                        "trigger" to trigger,
                        "deviceId" to result.device.id,
                        "host" to result.device.host,
                        "protocol" to result.device.protocolName,
                        "status" to result.status.name,
                        "latencyMillis" to result.latencyMillis,
                        "detectedName" to result.detectedName,
                        "detail" to result.detail,
                    ),
                )
            }
            val supportedCount = results.count { it.status == DeviceProbeStatus.Supported }
            _state.update {
                it.copy(
                    isProbing = false,
                    probeResults = results,
                    newSdkSupport = sdkSupport,
                    statusMessage = when {
                        route == null -> "未发现可用 Wi-Fi 网络，已写入诊断文件"
                        supportedCount > 0 -> "发现 $supportedCount 个受支持的记录仪协议"
                        else -> "没有发现受支持的旧款记录仪"
                    },
                )
            }
            diagnostics.log(
                "device_probe_run_completed",
                mapOf("trigger" to trigger, "supportedCount" to supportedCount),
            )
        }
    }

    suspend fun selectDevice(deviceId: String) {
        sessionMutex.withLock {
            closeActiveSessionLocked()
            val driver = drivers.firstOrNull { it.definition.id == deviceId }
                ?: return@withLock setOperationError("找不到设备协议驱动：$deviceId")
            val result = _state.value.probeResults.firstOrNull { it.device.id == deviceId }
            if (result?.status != DeviceProbeStatus.Supported) {
                return@withLock setOperationError("该设备尚未通过协议探测")
            }
            val route = networkResolver.findWifiRoute()
                ?: return@withLock setOperationError("Wi-Fi 网络已经断开")
            val bound = connectivityManager.bindProcessToNetwork(route.network)
            diagnostics.log(
                "device_session_opening",
                mapOf(
                    "deviceId" to deviceId,
                    "route" to route.description,
                    "processBound" to bound,
                ),
            )
            activeRoute = route
            activeSession = driver.createSession(route, diagnostics)
            _state.update {
                it.copy(
                    activeDevice = driver.definition,
                    statusMessage = "已连接 ${driver.definition.displayName}",
                    previewSource = null,
                    remoteMedia = emptyList(),
                    playbackMedia = null,
                    downloadedMedia = null,
                )
            }
        }
    }

    suspend fun startPreview() = runSessionOperation("正在准备实时预览…") { session ->
        val source = session.preparePreview()
        _state.update {
            it.copy(
                previewSource = source,
                playbackMedia = null,
                remoteMedia = emptyList(),
                statusMessage = "正在播放 ${session.device.displayName} 实时画面",
            )
        }
    }

    suspend fun loadRemoteMedia(category: RemoteMediaCategory) =
        runSessionOperation("正在读取${category.displayName}…") { session ->
            val media = session.loadRemoteMedia(category)
            _state.update {
                it.copy(
                    previewSource = null,
                    playbackMedia = null,
                    selectedCategory = category,
                    remoteMedia = media,
                    statusMessage = "读取到 ${media.size} 个${category.displayName}文件",
                )
            }
        }

    fun play(media: RemoteDeviceMedia) {
        _state.update {
            it.copy(
                playbackMedia = media,
                previewSource = null,
                statusMessage = "正在播放 ${media.name}",
            )
        }
        scope.launch {
            diagnostics.log(
                "device_remote_playback_started",
                mapOf(
                    "deviceId" to _state.value.activeDevice?.id,
                    "mediaId" to media.id,
                    "format" to media.format.name,
                    "uri" to media.playbackSource.uri,
                ),
            )
        }
    }

    suspend fun stopPlayer() {
        sessionMutex.withLock {
            val hadPreview = _state.value.previewSource != null
            _state.update { it.copy(playbackMedia = null, previewSource = null) }
            if (hadPreview) {
                activeSession?.let { session ->
                    runCatching { session.releasePreview() }
                        .onFailure { error ->
                            diagnostics.log(
                                "device_preview_release_failed",
                                mapOf("deviceId" to session.device.id, "error" to error.message),
                            )
                        }
                }
            }
        }
    }

    suspend fun download(media: RemoteDeviceMedia) =
        runSessionOperation("正在下载 ${media.name}…") { session ->
            val destination = File(
                appContext.getExternalFilesDir(Environment.DIRECTORY_MOVIES),
                "DashCam/devices/${session.device.id}",
            )
            val result = session.download(media, destination) { progress ->
                _state.update { it.copy(downloadProgress = progress) }
            }
            _state.update {
                it.copy(
                    downloadProgress = null,
                    downloadedMedia = result,
                    statusMessage = "已下载到 ${result.file.absolutePath}",
                )
            }
        }

    fun reportPlaybackError(source: DevicePlaybackSource, error: Throwable) {
        _state.update {
            it.copy(statusMessage = "播放失败：${error.message ?: error.javaClass.simpleName}")
        }
        scope.launch {
            diagnostics.log(
                "device_playback_failed",
                mapOf(
                    "deviceId" to _state.value.activeDevice?.id,
                    "source" to source.uri,
                    "sourceType" to source.javaClass.simpleName,
                    "error" to (error.message ?: error.javaClass.simpleName),
                ),
            )
        }
    }

    suspend fun closeActive() {
        sessionMutex.withLock {
            closeActiveSessionLocked()
        }
    }

    fun closeActiveAsync() {
        scope.launch { closeActive() }
    }

    private suspend fun runSessionOperation(
        message: String,
        operation: suspend (DeviceSession) -> Unit,
    ) {
        sessionMutex.withLock {
            val session = activeSession ?: return@withLock setOperationError("请先选择已探测到的设备")
            _state.update { it.copy(isBusy = true, statusMessage = message) }
            runCatching { operation(session) }
                .onFailure { error ->
                    setOperationError(error.message ?: error.javaClass.simpleName)
                    diagnostics.log(
                        "device_operation_failed",
                        mapOf(
                            "deviceId" to session.device.id,
                            "operationMessage" to message,
                            "error" to (error.message ?: error.javaClass.simpleName),
                        ),
                    )
                }
            _state.update { it.copy(isBusy = false) }
        }
    }

    private suspend fun closeActiveSessionLocked() {
        activeSession?.let { session ->
            runCatching { session.close() }
                .onFailure { error ->
                    diagnostics.log(
                        "device_session_close_failed",
                        mapOf("deviceId" to session.device.id, "error" to error.message),
                    )
                }
        }
        activeSession = null
        activeRoute = null
        connectivityManager.bindProcessToNetwork(null)
        _state.update {
            it.copy(
                activeDevice = null,
                previewSource = null,
                remoteMedia = emptyList(),
                playbackMedia = null,
                downloadProgress = null,
            )
        }
    }

    private fun setOperationError(message: String) {
        _state.update { it.copy(isBusy = false, statusMessage = message, downloadProgress = null) }
    }

    private fun registerWifiObserver() {
        if (networkCallbackRegistered) {
            return
        }
        networkCallbackRegistered = true
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()
        connectivityManager.registerNetworkCallback(
            request,
            object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    if (network == lastObservedWifiNetwork) {
                        return
                    }
                    lastObservedWifiNetwork = network
                    scope.launch {
                        delay(WIFI_SETTLE_DELAY_MILLIS)
                        probeAll(trigger = "wifi_available")
                    }
                }

                override fun onLost(network: Network) {
                    if (network != lastObservedWifiNetwork) {
                        return
                    }
                    lastObservedWifiNetwork = null
                    scope.launch {
                        diagnostics.log("device_wifi_lost", mapOf("network" to network))
                    }
                }
            },
        )
    }

    companion object {
        private const val WIFI_SETTLE_DELAY_MILLIS = 750L

        @Volatile
        private var instance: DeviceManager? = null

        fun get(context: Context): DeviceManager =
            instance ?: synchronized(this) {
                instance ?: DeviceManager(
                    context = context,
                    drivers = listOf(
                        Legacy254DeviceDriver(),
                        Legacy169DeviceDriver(),
                    ),
                    newSdkProbe = UnavailableNewDeviceSdkProbe,
                ).also { instance = it }
            }
    }
}
