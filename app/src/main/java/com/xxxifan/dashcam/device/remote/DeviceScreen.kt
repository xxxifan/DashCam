package com.xxxifan.dashcam.device.remote

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xxxifan.dashcam.data.formatBytes
import kotlinx.coroutines.launch

private enum class DevicePage(val label: String) {
    Live("预览"),
    Files("文件"),
    Settings("设置"),
}

@Composable
fun DeviceScreen(
    padding: PaddingValues,
    manager: DeviceManager,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state by manager.state.collectAsStateWithLifecycle()
    var page by remember { mutableStateOf(DevicePage.Live) }
    var showDevicePicker by remember { mutableStateOf(state.activeDevice == null) }
    var wifiNetworks by remember { mutableStateOf<List<DeviceWifiNetwork>>(emptyList()) }
    var scanMessage by remember { mutableStateOf("正在查找记录仪…") }
    var isScanning by remember { mutableStateOf(false) }
    var scanAfterPermission by remember { mutableStateOf(false) }
    var openWifiPanel by remember { mutableStateOf(false) }
    var passwordDevice by remember { mutableStateOf<DeviceWifiNetwork?>(null) }

    suspend fun scanWifi(openPanelWhenDisabled: Boolean = true) {
        isScanning = true
        val result = manager.scanWifi()
        wifiNetworks = result.networks
        scanMessage = result.message
        isScanning = false
        if (!result.wifiEnabled && openPanelWhenDisabled) {
            openWifiPanel = true
        }
    }

    val scanPermissions = arrayOf(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.NEARBY_WIFI_DEVICES,
    )
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        manager.reportWifiScanPermission(result)
        if (scanAfterPermission) {
            scanAfterPermission = false
            scope.launch { scanWifi() }
        }
    }
    val wifiPanelLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        scope.launch { scanWifi(openPanelWhenDisabled = false) }
    }
    LaunchedEffect(openWifiPanel) {
        if (openWifiPanel) {
            openWifiPanel = false
            wifiPanelLauncher.launch(Intent(Settings.Panel.ACTION_WIFI))
        }
    }
    val requestScan: () -> Unit = {
        val missing = scanPermissions.filter { permission ->
            ContextCompat.checkSelfPermission(context, permission) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            scope.launch { scanWifi() }
        } else {
            scanAfterPermission = true
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    LaunchedEffect(manager) {
        manager.probeAll(trigger = "device_tab_opened")
    }
    LaunchedEffect(Unit) {
        if (scanPermissions.all { permission ->
                ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
            }
        ) {
            scanWifi()
        } else {
            scanMessage = "允许附近 Wi-Fi 权限后可发现记录仪"
        }
    }
    LaunchedEffect(state.activeDevice?.id) {
        if (state.activeDevice != null) {
            showDevicePicker = false
            page = DevicePage.Live
        }
    }
    LaunchedEffect(
        page,
        state.activeDevice?.id,
        showDevicePicker,
        state.playbackMedia?.id,
        state.previewSource?.uri,
    ) {
        if (
            page == DevicePage.Live &&
            state.activeDevice != null &&
            !showDevicePicker &&
            state.previewSource == null &&
            state.playbackMedia == null &&
            !state.isBusy
        ) {
            manager.startPreview()
        }
    }

    state.playbackMedia?.let { media ->
        RemoteVideoPlaybackScreen(
            media = media,
            mediaList = state.remoteMedia,
            onPrevious = { previous -> manager.play(previous) },
            onNext = { next -> manager.play(next) },
            onDismiss = { scope.launch { manager.stopPlayer() } },
            onError = { source, error -> manager.reportPlaybackError(source, error) },
            onDiagnostic = { source, event, fields ->
                manager.reportPlaybackDiagnostic(source, event, fields)
            },
        )
        return
    }

    if (state.activeDevice == null || showDevicePicker) {
        passwordDevice?.let { device ->
            WifiPasswordDialog(
                deviceName = device.ssid,
                onDismiss = { passwordDevice = null },
                onConnect = { passphrase ->
                    passwordDevice = null
                    scope.launch { manager.connectWifi(device, passphrase) }
                },
            )
        }
        DevicePicker(
            padding = padding,
            state = state,
            networks = wifiNetworks,
            scanMessage = scanMessage,
            isScanning = isScanning || state.isProbing,
            onScan = requestScan,
            onConnectCurrent = { scope.launch { manager.probeAll(trigger = "user_connect") } },
            onConnectNetwork = { network ->
                when {
                    network.isCurrent -> scope.launch {
                        manager.probeAll(trigger = "user_connect")
                    }
                    network.security == DeviceWifiSecurity.Open -> scope.launch {
                        manager.connectWifi(network, null)
                    }
                    else -> passwordDevice = network
                }
            },
            onForget = { scope.launch { manager.forgetDevice() } },
            onBackToDevice = { showDevicePicker = false },
        )
        return
    }

    Column(
        modifier = Modifier
            .padding(padding)
            .fillMaxSize(),
    ) {
        ConnectedDeviceHeader(
            state = state,
            page = page,
            onPageChange = { destination ->
                page = destination
                scope.launch { manager.stopPlayer() }
            },
            onChangeDevice = {
                showDevicePicker = true
                scope.launch { manager.closeActive() }
            },
        )
        when (page) {
            DevicePage.Live -> LivePreviewPage(
                state = state,
                onRetry = {
                    scope.launch {
                        manager.stopPlayer()
                        manager.startPreview()
                    }
                },
                onError = { source, error -> manager.reportPlaybackError(source, error) },
                onDiagnostic = { source, event, fields ->
                    manager.reportPlaybackDiagnostic(source, event, fields)
                },
            )

            DevicePage.Files -> DeviceFilesPage(
                state = state,
                manager = manager,
            )

            DevicePage.Settings -> DeviceSettingsPage(
                state = state,
                onDisconnect = { scope.launch { manager.closeActive() } },
                onForget = { scope.launch { manager.forgetDevice() } },
            )
        }
    }
}

@Composable
internal fun RemoteVideoPlaybackScreen(
    media: RemoteDeviceMedia,
    mediaList: List<RemoteDeviceMedia>,
    onPrevious: (RemoteDeviceMedia) -> Unit,
    onNext: (RemoteDeviceMedia) -> Unit,
    onDismiss: () -> Unit,
    onError: (DevicePlaybackSource, Throwable) -> Unit,
    onDiagnostic: (DevicePlaybackSource, String, Map<String, Any?>) -> Unit,
) {
    val context = LocalContext.current
    val activity = context.findActivity()
    var isLandscape by remember(media.id) { mutableStateOf<Boolean?>(null) }
    val playableMedia = remember(mediaList) {
        mediaList.filter { it.format != RemoteMediaFormat.Jpeg }
    }
    val currentIndex = playableMedia.indexOfFirst { it.id == media.id }
    val previous = playableMedia.getOrNull(currentIndex - 1)
    val next = playableMedia.getOrNull(currentIndex + 1)

    BackHandler(onBack = onDismiss)
    DisposableEffect(activity, isLandscape) {
        val previousOrientation = activity?.requestedOrientation
        if (activity != null && isLandscape == true) {
            activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }
        onDispose {
            if (activity != null && previousOrientation != null) {
                activity.requestedOrientation = previousOrientation
            }
        }
    }

    Surface(
        color = Color.Black,
        modifier = Modifier.fillMaxSize(),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding(),
        ) {
            DevicePlayer(
                source = media.playbackSource,
                showControls = true,
                onError = { onError(media.playbackSource, it) },
                onDiagnostic = { event, fields ->
                    onDiagnostic(media.playbackSource, event, fields)
                },
                onVideoSizeChanged = { width, height ->
                    if (width > 0 && height > 0) {
                        isLandscape = width > height
                    }
                },
                showNavigationControls = false,
                modifier = Modifier.fillMaxSize(),
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.48f))
                    .padding(horizontal = 8.dp, vertical = 6.dp)
                    .align(Alignment.TopCenter),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Filled.Close, contentDescription = "关闭", tint = Color.White)
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        media.createdAtMillis?.formatRemoteMediaTimestamp() ?: media.name,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        media.name,
                        color = Color(0xFFCBD5E1),
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .background(Color.Black.copy(alpha = 0.48f), RoundedCornerShape(24.dp))
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                IconButton(onClick = { previous?.let(onPrevious) }, enabled = previous != null) {
                    Icon(Icons.Filled.SkipPrevious, contentDescription = "上一个视频", tint = Color.White)
                }
                Text(
                    if (currentIndex >= 0) "${currentIndex + 1}/${playableMedia.size}" else "视频",
                    color = Color.White,
                    style = MaterialTheme.typography.labelLarge,
                )
                IconButton(onClick = { next?.let(onNext) }, enabled = next != null) {
                    Icon(Icons.Filled.SkipNext, contentDescription = "下一个视频", tint = Color.White)
                }
            }
        }
    }
}

@Composable
private fun DevicePicker(
    padding: PaddingValues,
    state: DeviceUiState,
    networks: List<DeviceWifiNetwork>,
    scanMessage: String,
    isScanning: Boolean,
    onScan: () -> Unit,
    onConnectCurrent: () -> Unit,
    onConnectNetwork: (DeviceWifiNetwork) -> Unit,
    onForget: () -> Unit,
    onBackToDevice: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier.padding(padding),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("连接记录仪", style = MaterialTheme.typography.headlineSmall)
                    Text(
                        "选择附近的记录仪热点",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (state.activeDevice != null) {
                    TextButton(onClick = onBackToDevice) { Text("返回") }
                }
            }
        }

        state.rememberedDevice?.let { remembered ->
            item {
                RememberedDeviceCard(
                    remembered = remembered,
                    isBusy = state.isProbing,
                    onReconnect = onConnectCurrent,
                    onForget = onForget,
                )
            }
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("附近设备", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                IconButton(onClick = onScan, enabled = !isScanning) {
                    if (isScanning) {
                        CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.Filled.Refresh, contentDescription = "重新扫描")
                    }
                }
            }
        }

        items(networks, key = { "${it.ssid}-${it.bssid}" }) { network ->
            WifiDeviceCard(
                network = network,
                onConnect = { onConnectNetwork(network) },
            )
        }

        if (networks.isEmpty()) {
            item {
                EmptyWifiState(
                    message = scanMessage,
                    onScan = onScan,
                )
            }
        }

        item {
            Text(
                state.statusMessage,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun RememberedDeviceCard(
    remembered: RememberedDevice,
    isBusy: Boolean,
    onReconnect: () -> Unit,
    onForget: () -> Unit,
) {
    Card(
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.CheckCircle, contentDescription = null)
                Spacer(Modifier.size(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(remembered.ssid ?: "上次连接的记录仪", fontWeight = FontWeight.SemiBold)
                    Text(
                        "将优先使用 ${remembered.connectionMethod.displayName()} 连接",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onReconnect, enabled = !isBusy) { Text("重新连接") }
                TextButton(onClick = onForget, enabled = !isBusy) { Text("忘记设备") }
            }
        }
    }
}

@Composable
private fun WifiDeviceCard(
    network: DeviceWifiNetwork,
    onConnect: () -> Unit,
) {
    Card(shape = RoundedCornerShape(8.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Filled.Wifi,
                contentDescription = null,
                tint = signalColor(network.signalLevel),
                modifier = Modifier.size(28.dp),
            )
            Spacer(Modifier.size(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(network.ssid, fontWeight = FontWeight.SemiBold)
                Text(
                    when {
                        network.isCurrent -> "当前 Wi-Fi"
                        network.security == DeviceWifiSecurity.Open -> "可直接连接"
                        else -> "需要密码"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Button(onClick = onConnect) {
                Text(if (network.isCurrent) "连接" else "前往连接")
            }
        }
    }
}

@Composable
private fun EmptyWifiState(
    message: String,
    onScan: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            Icons.Filled.Wifi,
            contentDescription = null,
            modifier = Modifier.size(42.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedButton(onClick = onScan) {
            Icon(Icons.Filled.Refresh, contentDescription = null)
            Text("重新扫描")
        }
    }
}

@Composable
private fun WifiPasswordDialog(
    deviceName: String,
    onDismiss: () -> Unit,
    onConnect: (String) -> Unit,
) {
    var passphrase by remember(deviceName) { mutableStateOf("") }
    val isValid = passphrase.length in 8..63
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("连接 $deviceName") },
        text = {
            OutlinedTextField(
                value = passphrase,
                onValueChange = { passphrase = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Wi-Fi 密码") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
            )
        },
        confirmButton = {
            Button(
                onClick = { onConnect(passphrase) },
                enabled = isValid,
            ) {
                Text("连接")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

@Composable
private fun ConnectedDeviceHeader(
    state: DeviceUiState,
    page: DevicePage,
    onPageChange: (DevicePage) -> Unit,
    onChangeDevice: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .background(Color(0xFF16A34A), RoundedCornerShape(5.dp)),
            )
            Spacer(Modifier.size(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    state.rememberedDevice?.ssid ?: state.activeDevice?.displayName.orEmpty(),
                    fontWeight = FontWeight.SemiBold,
                )
                Text("已连接", style = MaterialTheme.typography.bodySmall, color = Color(0xFF15803D))
            }
            TextButton(onClick = onChangeDevice) { Text("更换设备") }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            DevicePage.entries.forEach { destination ->
                FilterChip(
                    selected = page == destination,
                    onClick = { onPageChange(destination) },
                    label = { Text(destination.label) },
                    leadingIcon = {
                        Icon(
                            when (destination) {
                                DevicePage.Live -> Icons.Filled.Videocam
                                DevicePage.Files -> Icons.Filled.Folder
                                DevicePage.Settings -> Icons.Filled.Settings
                            },
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                    },
                )
            }
        }
    }
}

@Composable
private fun LivePreviewPage(
    state: DeviceUiState,
    onRetry: () -> Unit,
    onError: (DevicePlaybackSource, Throwable) -> Unit,
    onDiagnostic: (DevicePlaybackSource, String, Map<String, Any?>) -> Unit,
) {
    Column(
        modifier = Modifier.padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        val source = state.previewSource
        Card(shape = RoundedCornerShape(8.dp)) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .background(Color.Black),
                contentAlignment = Alignment.Center,
            ) {
                if (source != null) {
                    DevicePlayer(
                        source = source,
                        showControls = false,
                        onError = { onError(source, it) },
                        onDiagnostic = { event, fields -> onDiagnostic(source, event, fields) },
                        modifier = Modifier.fillMaxSize(),
                    )
                } else if (state.isBusy) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("正在连接实时画面", color = Color.White)
                    }
                } else {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Filled.Warning, contentDescription = null, tint = Color.White)
                        Spacer(Modifier.height(8.dp))
                        Text("实时画面暂不可用", color = Color.White)
                    }
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("实时预览", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    state.statusMessage,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            OutlinedButton(onClick = onRetry, enabled = !state.isBusy) {
                Icon(Icons.Filled.Refresh, contentDescription = null)
                Text("重连")
            }
        }
    }
}

@Composable
private fun DeviceFilesPage(
    state: DeviceUiState,
    manager: DeviceManager,
) {
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val supportedCategories = state.supportedCategories
        .ifEmpty { RemoteMediaCategory.entries.toSet() }
    var selectedCategory by remember(state.activeDevice?.id) {
        mutableStateOf(
            RemoteMediaCategory.NormalVideo.takeIf { it in supportedCategories }
                ?: supportedCategories.first(),
        )
    }
    var expandedMediaId by remember(state.activeDevice?.id) { mutableStateOf<String?>(null) }
    var selectedTimelineMediaId by remember(state.activeDevice?.id) { mutableStateOf<String?>(null) }
    val showTimeline = selectedCategory != RemoteMediaCategory.Photo && state.remoteMedia.isNotEmpty()
    val firstMediaItemIndex = if (showTimeline) 3 else 2

    LaunchedEffect(supportedCategories) {
        if (selectedCategory !in supportedCategories) {
            selectedCategory = supportedCategories.first()
        }
    }
    LaunchedEffect(selectedCategory, state.activeDevice?.id) {
        expandedMediaId = null
        selectedTimelineMediaId = null
        manager.loadRemoteMedia(selectedCategory)
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        state = listState,
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text("设备文件", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        }
        state.playbackMedia?.let { media ->
            item(key = "player-${media.id}") {
                Card(shape = RoundedCornerShape(8.dp)) {
                    Column {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                media.name,
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                fontWeight = FontWeight.Medium,
                            )
                            TextButton(onClick = { scope.launch { manager.stopPlayer() } }) {
                                Text("关闭")
                            }
                        }
                        DevicePlayer(
                            source = media.playbackSource,
                            showControls = true,
                            onError = { manager.reportPlaybackError(media.playbackSource, it) },
                            onDiagnostic = { event, fields ->
                                manager.reportPlaybackDiagnostic(media.playbackSource, event, fields)
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .aspectRatio(16f / 9f),
                        )
                    }
                }
            }
        }
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                supportedCategories.forEach { category ->
                    FilterChip(
                        selected = selectedCategory == category,
                        onClick = { selectedCategory = category },
                        label = { Text(category.shortName()) },
                    )
                }
            }
        }
        if (showTimeline) {
            item(key = "media-timeline") {
                DeviceMediaTimeline(
                    media = state.remoteMedia,
                    selectedMediaId = selectedTimelineMediaId,
                    onSelect = { index, selected ->
                        selectedTimelineMediaId = selected.id
                        expandedMediaId = selected.id
                        scope.launch {
                            listState.animateScrollToItem(firstMediaItemIndex + index)
                        }
                    },
                )
            }
        }
        if (state.isBusy && state.remoteMedia.isEmpty()) {
            item { LinearProgressIndicator(modifier = Modifier.fillMaxWidth()) }
        }
        if (!state.isBusy && state.remoteMedia.isEmpty()) {
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 36.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(
                        if (selectedCategory == RemoteMediaCategory.Photo) {
                            Icons.Filled.Image
                        } else {
                            Icons.Filled.Movie
                        },
                        contentDescription = null,
                        modifier = Modifier.size(42.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(10.dp))
                    Text("没有${selectedCategory.displayName}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        itemsIndexed(state.remoteMedia, key = { _, media -> media.id }) { _, media ->
            RemoteMediaItem(
                media = media,
                progress = state.downloadProgress?.takeIf { it.mediaId == media.id },
                enabled = !state.isBusy,
                expanded = expandedMediaId == media.id,
                manager = manager,
                onToggleExpanded = {
                    expandedMediaId = media.id.takeUnless { it == expandedMediaId }
                    selectedTimelineMediaId = media.id
                },
                onPlay = { manager.play(media) },
                onDownload = { scope.launch { manager.download(media) } },
            )
        }
        state.downloadedMedia?.let { downloaded ->
            item {
                Text(
                    "已保存 ${downloaded.file.name}",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF15803D),
                )
            }
        }
    }
}

@Composable
private fun DeviceMediaTimeline(
    media: List<RemoteDeviceMedia>,
    selectedMediaId: String?,
    onSelect: (index: Int, media: RemoteDeviceMedia) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("时间标尺", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        LazyRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            itemsIndexed(media, key = { _, item -> "timeline-${item.id}" }) { index, item ->
                val selected = selectedMediaId == item.id
                val color = if (selected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.outline
                }
                Column(
                    modifier = Modifier
                        .width(76.dp)
                        .clickable { onSelect(index, item) }
                        .padding(vertical = 4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(
                        modifier = Modifier
                            .width(2.dp)
                            .height(if (selected) 22.dp else 14.dp)
                            .background(color, RoundedCornerShape(1.dp)),
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        item.createdAtMillis?.formatRemoteTimelineTime() ?: "--:--:--",
                        style = MaterialTheme.typography.labelMedium,
                        color = color,
                        maxLines = 1,
                    )
                    Text(
                        item.createdAtMillis?.formatRemoteTimelineDate() ?: "日期未知",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

@Composable
private fun RemoteMediaItem(
    media: RemoteDeviceMedia,
    progress: DeviceDownloadProgress?,
    enabled: Boolean,
    expanded: Boolean,
    manager: DeviceManager,
    onToggleExpanded: () -> Unit,
    onPlay: () -> Unit,
    onDownload: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggleExpanded),
        shape = RoundedCornerShape(8.dp),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (media.format == RemoteMediaFormat.Jpeg) Icons.Filled.Image else Icons.Filled.Movie,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.size(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        media.createdAtMillis?.formatRemoteMediaTimestamp() ?: media.name,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        buildString {
                            append(media.format.displayName())
                            media.sizeBytes?.let { append(" · ${it.formatBytes()}") }
                            media.durationMillis?.let { append(" · ${it.formatRemoteDuration()}") }
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (media.format != RemoteMediaFormat.Jpeg) {
                    IconButton(onClick = onPlay, enabled = enabled) {
                        Icon(Icons.Filled.PlayArrow, contentDescription = "播放")
                    }
                }
                IconButton(onClick = onDownload, enabled = enabled) {
                    Icon(Icons.Filled.CloudDownload, contentDescription = "下载")
                }
            }
            if (expanded) {
                Text(
                    media.name,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                RemoteMediaThumbnail(
                    media = media,
                    manager = manager,
                    onPlay = onPlay.takeIf { media.format != RemoteMediaFormat.Jpeg },
                )
            }
            progress?.let {
                it.fraction?.let { fraction ->
                    LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
                } ?: LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun RemoteMediaThumbnail(
    media: RemoteDeviceMedia,
    manager: DeviceManager,
    onPlay: (() -> Unit)?,
) {
    var bitmap by remember(media.id) { mutableStateOf<Bitmap?>(null) }
    var loading by remember(media.id) { mutableStateOf(true) }

    LaunchedEffect(media.id) {
        loading = true
        bitmap = manager.loadThumbnail(media)
        loading = false
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(16f / 9f)
            .background(Color.Black, RoundedCornerShape(6.dp))
            .clickable(enabled = onPlay != null) { onPlay?.invoke() },
        contentAlignment = Alignment.Center,
    ) {
        bitmap?.let { thumbnail ->
            Image(
                bitmap = thumbnail.asImageBitmap(),
                contentDescription = "${media.name} 缩略图",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        }
        when {
            loading -> CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp)
            bitmap == null -> Text("缩略图不可用", color = Color.White)
            onPlay != null -> Box(
                modifier = Modifier
                    .size(48.dp)
                    .background(Color.Black.copy(alpha = 0.58f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.PlayArrow, contentDescription = "播放", tint = Color.White)
            }
        }
    }
}

@Composable
private fun DeviceSettingsPage(
    state: DeviceUiState,
    onDisconnect: () -> Unit,
    onForget: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text("记录仪设置", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        }
        item {
            Card(shape = RoundedCornerShape(8.dp)) {
                Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                    SettingValueRow(
                        "设备",
                        state.rememberedDevice?.ssid ?: state.activeDevice?.displayName.orEmpty(),
                    )
                    HorizontalDivider()
                    SettingValueRow("产品型号", state.activeDevice?.model?.displayName.orEmpty())
                    HorizontalDivider()
                    SettingValueRow(
                        "连接方式",
                        state.activeDevice?.connectionMethod?.displayName().orEmpty(),
                    )
                    state.storageInfo?.let { storage ->
                        HorizontalDivider()
                        Column(modifier = Modifier.padding(vertical = 14.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text("存储卡")
                                Text(
                                    "可用 ${storage.freeBytes.formatBytes()}",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Spacer(Modifier.height(8.dp))
                            LinearProgressIndicator(
                                progress = {
                                    if (storage.totalBytes > 0L) {
                                        storage.usedBytes.toFloat() / storage.totalBytes.toFloat()
                                    } else {
                                        0f
                                    }
                                },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
            }
        }
        item {
            Card(
                shape = RoundedCornerShape(8.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text("设备参数", fontWeight = FontWeight.SemiBold)
                    Text(
                        state.settingsSupport.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        item {
            OutlinedButton(onClick = onDisconnect, modifier = Modifier.fillMaxWidth()) {
                Text("断开连接")
            }
        }
        item {
            TextButton(onClick = onForget, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Filled.DeleteOutline, contentDescription = null)
                Text("忘记此设备")
            }
        }
    }
}

@Composable
private fun SettingValueRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 14.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label)
        Text(
            value,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private fun DeviceConnectionMethod.displayName(): String = when (this) {
    DeviceConnectionMethod.Legacy -> "Legacy"
    DeviceConnectionMethod.VendorSdk -> "厂商 SDK"
}

private fun RemoteMediaCategory.shortName(): String = when (this) {
    RemoteMediaCategory.NormalVideo -> "普通视频"
    RemoteMediaCategory.EmergencyVideo -> "紧急视频"
    RemoteMediaCategory.Photo -> "照片"
}

private fun RemoteMediaFormat.displayName(): String = when (this) {
    RemoteMediaFormat.Mp4 -> "MP4"
    RemoteMediaFormat.Mov -> "MOV"
    RemoteMediaFormat.TransportStream -> "TS"
    RemoteMediaFormat.Jpeg -> "JPEG"
    RemoteMediaFormat.Unknown -> "文件"
}

private fun signalColor(rssi: Int): Color = when {
    rssi >= -55 -> Color(0xFF15803D)
    rssi >= -72 -> Color(0xFFD97706)
    else -> Color(0xFF64748B)
}

private fun Context.findActivity(): Activity? {
    var current = this
    while (current is ContextWrapper) {
        if (current is Activity) {
            return current
        }
        current = current.baseContext
    }
    return null
}
