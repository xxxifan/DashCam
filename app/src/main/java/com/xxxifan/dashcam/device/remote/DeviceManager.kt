package com.xxxifan.dashcam.device.remote

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Environment
import androidx.media3.common.PlaybackException
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
    private val wifiScanner = DeviceWifiScanner(appContext)
    private val wifiConnector = DeviceWifiConnector(appContext)
    private val diagnostics = DeviceDiagnosticsLogger(appContext)
    private val preferences = DevicePreferencesStore()
    private val probeMutex = Mutex()
    private val sessionMutex = Mutex()
    private var activeSession: DeviceSession? = null
    private var activeRoute: DeviceNetworkRoute? = null
    private var networkCallbackRegistered = false
    private var lastObservedWifiNetwork: Network? = null

    private val _state = MutableStateFlow(
        DeviceUiState(
            diagnosticFile = diagnostics.currentFile(),
            rememberedDevice = preferences.rememberedDevice(),
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
            val orderedDrivers = drivers.sortedWith(driverPreferenceComparator())
            diagnostics.log(
                "device_driver_order_resolved",
                mapOf(
                    "trigger" to trigger,
                    "currentSsid" to wifiScanner.currentSsid(),
                    "rememberedDriverId" to preferences.rememberedDevice()?.driverId,
                    "rememberedMethod" to preferences.rememberedDevice()?.connectionMethod?.name,
                    "orderedDriverIds" to orderedDrivers.joinToString(",") { it.definition.id },
                ),
            )
            val results = if (route == null) {
                orderedDrivers.map { driver ->
                    DeviceProbeResult(
                        device = driver.definition,
                        status = DeviceProbeStatus.Unreachable,
                        detail = "当前没有可用于局域网探测的 Wi-Fi 网络",
                    )
                }
            } else {
                coroutineScope {
                    orderedDrivers.map { driver ->
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
                        route == null -> "请先连接记录仪 Wi-Fi"
                        supportedCount > 0 -> "已识别记录仪，正在连接"
                        else -> "当前 Wi-Fi 不是受支持的记录仪"
                    },
                )
            }
            diagnostics.log(
                "device_probe_run_completed",
                mapOf("trigger" to trigger, "supportedCount" to supportedCount),
            )
            if (_state.value.activeDevice == null) {
                preferredSupportedResult(results)?.let { result ->
                    diagnostics.log(
                        "device_driver_selected",
                        mapOf(
                            "trigger" to trigger,
                            "deviceId" to result.device.id,
                            "model" to result.device.model.name,
                            "connectionMethod" to result.device.connectionMethod.name,
                            "rememberedMatch" to (
                                result.device.id == preferences.rememberedDevice()?.driverId
                                ),
                        ),
                    )
                    selectDevice(result.device.id)
                }
            }
        }
    }

    suspend fun scanWifi(): DeviceWifiScanResult {
        diagnostics.log(
            "device_wifi_scan_started",
            mapOf("currentSsid" to wifiScanner.currentSsid()),
        )
        val result = wifiScanner.scan()
        diagnostics.log(
            "device_wifi_scan_completed",
            mapOf(
                "scanRequested" to result.scanRequested,
                "rawNetworkCount" to result.rawNetworkCount,
                "supportedNetworkCount" to result.networks.size,
                "filteredNetworkCount" to (result.rawNetworkCount - result.networks.size).coerceAtLeast(0),
                "failureType" to result.failureType,
                "message" to result.message,
                "candidates" to result.networks.joinToString("|") { network ->
                    "${network.ssid},${network.bssid.maskBssid()},${network.model.name}," +
                        "${network.security.name},current=${network.isCurrent},rssi=${network.signalLevel}"
                },
            ),
        )
        return result
    }

    fun reportWifiScanPermission(result: Map<String, Boolean>) {
        scope.launch {
            diagnostics.log(
                "device_wifi_scan_permission_result",
                mapOf(
                    "fineLocation" to result[android.Manifest.permission.ACCESS_FINE_LOCATION],
                    "nearbyWifi" to result[android.Manifest.permission.NEARBY_WIFI_DEVICES],
                ),
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
            val session = driver.createSession(route, diagnostics)
            activeSession = session
            val storageInfo = runCatching { session.loadStorageInfo() }
                .onFailure { error ->
                    diagnostics.log(
                        "device_storage_info_failed",
                        mapOf(
                            "deviceId" to deviceId,
                            "error" to (error.message ?: error.javaClass.simpleName),
                        ),
                    )
                }
                .getOrNull()
            val remembered = preferences.remember(
                ssid = wifiScanner.currentSsid(),
                driverId = driver.definition.id,
                connectionMethod = driver.definition.connectionMethod,
            )
            _state.update {
                it.copy(
                    activeDevice = driver.definition,
                    rememberedDevice = remembered,
                    statusMessage = "已连接 ${driver.definition.displayName}",
                    previewSource = null,
                    remoteMedia = emptyList(),
                    storageInfo = storageInfo,
                    settingsSupport = session.settingsSupport,
                    supportedCategories = session.supportedCategories,
                    playbackMedia = null,
                    downloadedMedia = null,
                )
            }
            diagnostics.log(
                "device_connection_remembered",
                mapOf(
                    "ssid" to remembered.ssid,
                    "driverId" to remembered.driverId,
                    "connectionMethod" to remembered.connectionMethod.name,
                ),
            )
        }
    }

    suspend fun connectWifi(
        device: DeviceWifiNetwork,
        passphrase: String?,
    ) {
        _state.update {
            it.copy(
                isBusy = true,
                statusMessage = "正在连接 ${device.ssid}…",
            )
        }
        diagnostics.log(
            "device_wifi_connection_requested",
            mapOf(
                "ssid" to device.ssid,
                "bssid" to device.bssid.maskBssid(),
                "model" to device.model.name,
                "security" to device.security.name,
                "passphraseProvided" to !passphrase.isNullOrEmpty(),
            ),
        )
        val connectionStartedAt = System.currentTimeMillis()
        wifiConnector.connect(device, passphrase)
            .onSuccess { network ->
                diagnostics.log(
                    "device_wifi_connection_available",
                    mapOf(
                        "ssid" to device.ssid,
                        "network" to network,
                        "elapsedMillis" to (System.currentTimeMillis() - connectionStartedAt),
                    ),
                )
                _state.update {
                    it.copy(
                        isBusy = false,
                        statusMessage = "已连接 ${device.ssid}，正在识别设备",
                    )
                }
                delay(WIFI_SETTLE_DELAY_MILLIS)
                probeAll(trigger = "wifi_device_selected")
            }
            .onFailure { error ->
                diagnostics.log(
                    "device_wifi_connection_failed",
                    mapOf(
                        "ssid" to device.ssid,
                        "elapsedMillis" to (System.currentTimeMillis() - connectionStartedAt),
                        "error" to (error.message ?: error.javaClass.simpleName),
                        "errorType" to error.javaClass.name,
                    ),
                )
                setOperationError(error.message ?: "连接记录仪 Wi-Fi 失败")
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
                    "errorType" to error.javaClass.name,
                    "errorCodeName" to (error as? PlaybackException)?.errorCodeName,
                    "errorChain" to error.diagnosticCauseChain(),
                ),
            )
        }
    }

    fun reportPlaybackDiagnostic(
        source: DevicePlaybackSource,
        event: String,
        fields: Map<String, Any?> = emptyMap(),
    ) {
        scope.launch {
            diagnostics.log(
                event,
                mapOf(
                    "deviceId" to _state.value.activeDevice?.id,
                    "source" to source.uri,
                    "sourceType" to source.javaClass.simpleName,
                ) + fields,
            )
        }
    }

    suspend fun closeActive() {
        sessionMutex.withLock {
            closeActiveSessionLocked()
        }
    }

    suspend fun forgetDevice() {
        sessionMutex.withLock {
            closeActiveSessionLocked()
            wifiConnector.release()
            preferences.forget()
            diagnostics.log("device_connection_forgotten")
            _state.update {
                it.copy(
                    rememberedDevice = null,
                    statusMessage = "已忘记记录仪",
                )
            }
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
                storageInfo = null,
                settingsSupport = DeviceSettingsSupport.Unsupported,
                supportedCategories = emptySet(),
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
                        if (activeRoute?.network == network) {
                            closeActive()
                            _state.update {
                                it.copy(statusMessage = "记录仪 Wi-Fi 已断开，等待重新连接")
                            }
                        }
                    }
                }
            },
        )
    }

    private fun driverPreferenceComparator(): Comparator<DeviceProtocolDriver> {
        val remembered = preferences.rememberedDevice()
        return compareBy<DeviceProtocolDriver> {
            if (it.definition.id == remembered?.driverId) 0 else 1
        }.thenBy {
            if (it.definition.connectionMethod == remembered?.connectionMethod) 0 else 1
        }.thenBy {
            if (
                it.definition.model == DeviceModel.Dc1 &&
                it.definition.connectionMethod == DeviceConnectionMethod.Legacy
            ) {
                0
            } else {
                1
            }
        }
    }

    private fun preferredSupportedResult(results: List<DeviceProbeResult>): DeviceProbeResult? {
        val remembered = preferences.rememberedDevice()
        val currentModel = wifiScanner.currentSsid()?.deviceModelOrNull()
        return selectPreferredSupportedResult(results, remembered, currentModel)
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

internal fun selectPreferredSupportedResult(
    results: List<DeviceProbeResult>,
    remembered: RememberedDevice?,
    currentModel: DeviceModel?,
): DeviceProbeResult? {
    val supported = results.filter { it.status == DeviceProbeStatus.Supported }
    return supported.firstOrNull { it.device.id == remembered?.driverId }
        ?: supported.firstOrNull { it.device.connectionMethod == remembered?.connectionMethod }
        ?: supported.firstOrNull { it.device.model == currentModel }
        ?: supported.firstOrNull {
            it.device.model == DeviceModel.Dc1 &&
                it.device.connectionMethod == DeviceConnectionMethod.Legacy
        }
        ?: supported.firstOrNull()
}

private fun Throwable.diagnosticCauseChain(): String {
    val causes = mutableListOf<String>()
    val visited = mutableSetOf<Throwable>()
    var current: Throwable? = this
    while (current != null && visited.add(current) && causes.size < 8) {
        causes += buildString {
            append(current.javaClass.name)
            current.message?.takeIf { it.isNotBlank() }?.let { message ->
                append(": ")
                append(message)
            }
        }
        current = current.cause
    }
    return causes.joinToString(" <- ")
}

private fun String.maskBssid(): String {
    val parts = split(':')
    return if (parts.size == 6) {
        "**:**:**:**:${parts[4]}:${parts[5]}"
    } else {
        "unknown"
    }
}
