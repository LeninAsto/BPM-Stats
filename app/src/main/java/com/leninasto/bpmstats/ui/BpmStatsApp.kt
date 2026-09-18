package com.leninasto.bpmstats.ui

import android.Manifest
import android.app.Activity
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ShowChart
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.BluetoothDisabled
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.PictureInPictureAlt
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SignalCellularAlt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.leninasto.bpmstats.R
import com.leninasto.bpmstats.ble.BleDeviceInfo
import com.leninasto.bpmstats.data.HeartRateEntry
import com.leninasto.bpmstats.monitor.DailyHeartRateSummary
import com.leninasto.bpmstats.monitor.HeartRateMonitorRepository
import com.leninasto.bpmstats.ui.theme.BPMStatsTheme
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.delay
import kotlin.math.max
import kotlin.math.roundToInt

private enum class ChartRange(val label: String, val durationMs: Long) {
    OneMinute("1 min", 60 * 1000L),
    TenMinutes("10 min", 10 * 60 * 1000L),
    OneHour("1 h", 60 * 60 * 1000L),
    SixHours("6 h", 6 * 60 * 60 * 1000L),
    OneDay("24 h", 24 * 60 * 60 * 1000L),
}

private data class ChartPoint(val bpm: Int, val elapsedMs: Long, val held: Boolean = false)

private data class ColorStopConfig(val ppm: Int, val color: Color)

private data class AppUiSettings(
    val alertsEnabled: Boolean = true,
    val lowAlert: Int = 55,
    val highAlert: Int = 165,
    val colorStops: List<ColorStopConfig> = defaultColorStops(4),
    val keepScreenOnFullscreen: Boolean = false,
    val showChartGrid: Boolean = true,
    val showPointMarkers: Boolean = true,
    val smoothChartLine: Boolean = true,
    val continuousTrace: Boolean = true,
)

private object AppSettingsStore {
    var settings by mutableStateOf(AppUiSettings())
        private set

    private var initialized = false
    private lateinit var preferences: android.content.SharedPreferences

    fun initialize(context: Context) {
        if (initialized) return
        preferences = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        settings = loadSettings()
        initialized = true
    }

    fun setAlertsEnabled(enabled: Boolean) = update { it.copy(alertsEnabled = enabled) }

    fun setLowAlert(value: Int) = update { current ->
        current.copy(lowAlert = value.coerceIn(35, current.highAlert - 5))
    }

    fun setHighAlert(value: Int) = update { current ->
        current.copy(highAlert = value.coerceIn(current.lowAlert + 5, 220))
    }

    fun setColorStops(stops: List<ColorStopConfig>) = update { it.copy(colorStops = stops.normalizedColorStops()) }

    fun setKeepScreenOnFullscreen(enabled: Boolean) = update { it.copy(keepScreenOnFullscreen = enabled) }

    fun setShowChartGrid(enabled: Boolean) = update { it.copy(showChartGrid = enabled) }

    fun setShowPointMarkers(enabled: Boolean) = update { it.copy(showPointMarkers = enabled) }

    fun setSmoothChartLine(enabled: Boolean) = update { it.copy(smoothChartLine = enabled) }

    fun setContinuousTrace(enabled: Boolean) = update { it.copy(continuousTrace = enabled) }

    private fun update(transform: (AppUiSettings) -> AppUiSettings) {
        settings = transform(settings)
        saveSettings(settings)
    }

    private fun loadSettings(): AppUiSettings {
        return AppUiSettings(
            alertsEnabled = preferences.getBoolean(KEY_ALERTS_ENABLED, true),
            lowAlert = preferences.getInt(KEY_LOW_ALERT, 55),
            highAlert = preferences.getInt(KEY_HIGH_ALERT, 165),
            colorStops = preferences.getString(KEY_COLOR_STOPS, null)?.decodeColorStops() ?: defaultColorStops(4),
            keepScreenOnFullscreen = preferences.getBoolean(KEY_KEEP_SCREEN_ON_FULLSCREEN, false),
            showChartGrid = preferences.getBoolean(KEY_SHOW_CHART_GRID, true),
            showPointMarkers = preferences.getBoolean(KEY_SHOW_POINT_MARKERS, true),
            smoothChartLine = preferences.getBoolean(KEY_SMOOTH_CHART_LINE, true),
            continuousTrace = preferences.getBoolean(KEY_CONTINUOUS_TRACE, true),
        )
    }

    private fun saveSettings(value: AppUiSettings) {
        preferences.edit()
            .putBoolean(KEY_ALERTS_ENABLED, value.alertsEnabled)
            .putInt(KEY_LOW_ALERT, value.lowAlert)
            .putInt(KEY_HIGH_ALERT, value.highAlert)
            .putString(KEY_COLOR_STOPS, value.colorStops.encodeColorStops())
            .putBoolean(KEY_KEEP_SCREEN_ON_FULLSCREEN, value.keepScreenOnFullscreen)
            .putBoolean(KEY_SHOW_CHART_GRID, value.showChartGrid)
            .putBoolean(KEY_SHOW_POINT_MARKERS, value.showPointMarkers)
            .putBoolean(KEY_SMOOTH_CHART_LINE, value.smoothChartLine)
            .putBoolean(KEY_CONTINUOUS_TRACE, value.continuousTrace)
            .apply()
    }

    private fun List<ColorStopConfig>.encodeColorStops(): String {
        return normalizedColorStops().joinToString("|") { "${it.ppm}:${it.color.toArgb()}" }
    }

    private fun String.decodeColorStops(): List<ColorStopConfig>? {
        val decoded = split("|").mapNotNull { token ->
            val parts = token.split(":")
            val ppm = parts.getOrNull(0)?.toIntOrNull()
            val argb = parts.getOrNull(1)?.toIntOrNull()
            if (ppm != null && argb != null) ColorStopConfig(ppm, Color(argb)) else null
        }
        return decoded.takeIf { it.size >= 2 }?.normalizedColorStops()
    }

    private const val PREFS_NAME = "ppm_stats_ui_settings"
    private const val KEY_ALERTS_ENABLED = "alerts_enabled"
    private const val KEY_LOW_ALERT = "low_alert"
    private const val KEY_HIGH_ALERT = "high_alert"
    private const val KEY_COLOR_STOPS = "color_stops"
    private const val KEY_KEEP_SCREEN_ON_FULLSCREEN = "keep_screen_on_fullscreen"
    private const val KEY_SHOW_CHART_GRID = "show_chart_grid"
    private const val KEY_SHOW_POINT_MARKERS = "show_point_markers"
    private const val KEY_SMOOTH_CHART_LINE = "smooth_chart_line"
    private const val KEY_CONTINUOUS_TRACE = "continuous_trace"
}

private val LiveChartRanges = listOf(
    ChartRange.OneMinute,
    ChartRange.TenMinutes,
    ChartRange.OneHour,
    ChartRange.SixHours,
)

@SuppressLint("MissingPermission")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BpmStatsApp(viewModel: HeartRateViewModel = viewModel()) {
    val context = LocalContext.current
    AppSettingsStore.initialize(context)
    val appSettings = AppSettingsStore.settings
    val currentBpm by viewModel.currentBpm.collectAsState()
    val connectionState by viewModel.connectionState.collectAsState()
    val discoveredDevices by viewModel.discoveredDevices.collectAsState()
    val connectedDevice by viewModel.connectedDevice.collectAsState()
    val signalRssi by viewModel.signalRssi.collectAsState()
    val batteryLevel by viewModel.batteryLevel.collectAsState()
    val sessionEntries by viewModel.sessionEntries.collectAsState()
    val savedEntries by viewModel.savedEntries.collectAsState()
    val dailySummaries by viewModel.dailySummaries.collectAsState()
    val aliases by viewModel.deviceAliases.collectAsState()

    var selectedTab by remember { mutableIntStateOf(0) }
    var showAliasDialog by remember { mutableStateOf(false) }
    var fullScreen by remember { mutableStateOf(false) }
    var selectedRange by remember { mutableStateOf(ChartRange.OneMinute) }

    val connectedName = connectedDevice?.let { aliases[it.address] ?: it.name }
    val colorStops = appSettings.colorStops
    val bpmColor = ppmGradientColor(currentBpm, colorStops)
    val alertState = when {
        !appSettings.alertsEnabled || currentBpm <= 0 -> null
        currentBpm < appSettings.lowAlert -> "Pulso bajo"
        currentBpm > appSettings.highAlert -> "Pulso alto"
        else -> null
    }

    val enableBluetoothLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (context.isBluetoothEnabled()) viewModel.startTracking()
    }
    val notificationPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        val granted = requiredBlePermissions().all { grants[it] == true }
        if (granted) {
            if (context.isBluetoothEnabled()) viewModel.startTracking()
            else enableBluetoothLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
        }
    }

    fun startBleFlow() {
        when {
            !context.hasBlePermissions() -> permissionLauncher.launch(requiredBlePermissions())
            !context.isBluetoothEnabled() -> enableBluetoothLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
            else -> viewModel.startTracking()
        }
    }

    fun connectDevice(address: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !context.hasPermission(Manifest.permission.POST_NOTIFICATIONS)) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        viewModel.connectToDevice(address)
    }

    if (fullScreen) {
        FullScreenMonitor(
            currentBpm = currentBpm,
            entries = sessionEntries,
            savedEntries = savedEntries,
            bpmColor = bpmColor,
            alertState = alertState,
            selectedRange = selectedRange,
            colorStops = colorStops,
            appSettings = appSettings,
            onExit = { fullScreen = false },
        )
        return
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                        Icon(painterResource(R.drawable.ic_ecg_heart), contentDescription = null, tint = bpmColor, modifier = Modifier.size(28.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("PPM Stats", fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                    }
                },
                actions = {
                    IconButton(onClick = { fullScreen = true }) {
                        Icon(Icons.Default.Fullscreen, contentDescription = "Pantalla completa")
                    }
                    IconButton(onClick = { context.startActivity(Intent(context, SettingsActivity::class.java)) }) {
                        Icon(Icons.Default.Settings, contentDescription = "Ajustes")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
            )
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    icon = { Icon(Icons.Default.Bluetooth, contentDescription = null) },
                    label = { Text("Monitor", textAlign = TextAlign.Center) },
                )
                NavigationBarItem(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    icon = { Icon(Icons.Default.SignalCellularAlt, contentDescription = null) },
                    label = { Text("Historial", textAlign = TextAlign.Center) },
                )
            }
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize(),
        ) {
            if (selectedTab == 0) {
                MonitorTab(
                    currentBpm = currentBpm,
                    connectionState = connectionState,
                    discoveredDevices = discoveredDevices,
                    connectedDevice = connectedDevice,
                    connectedName = connectedName,
                    signalRssi = signalRssi,
                    batteryLevel = batteryLevel,
                    entries = sessionEntries,
                    savedEntries = savedEntries,
                    bpmColor = bpmColor,
                    alertState = alertState,
                    selectedRange = selectedRange,
                    colorStops = colorStops,
                    appSettings = appSettings,
                    onSearch = ::startBleFlow,
                    onDisconnect = viewModel::stopTracking,
                    onDeviceSelected = ::connectDevice,
                    onAliasClick = { showAliasDialog = true },
                    onRangeSelected = { selectedRange = it },
                )
            } else {
                HistoryTab(summaries = dailySummaries, colorStops = colorStops, appSettings = appSettings)
            }

            if (connectedDevice != null) {
                FloatingPulseWidget(
                    bpm = currentBpm,
                    bpmColor = bpmColor,
                    alertState = alertState,
                    modifier = Modifier.align(Alignment.BottomEnd).padding(20.dp),
                )
            }
        }
    }

    if (showAliasDialog && connectedDevice != null) {
        AliasDialog(
            device = connectedDevice!!,
            initialAlias = aliases[connectedDevice!!.address].orEmpty(),
            onDismiss = { showAliasDialog = false },
            onSave = {
                viewModel.setDeviceAlias(connectedDevice!!.address, it)
                showAliasDialog = false
            },
        )
    }
}

class SettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        HeartRateMonitorRepository.initialize(this)
        AppSettingsStore.initialize(this)
        setContent {
            BPMStatsTheme {
                SettingsScreen(onBack = ::finish)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsScreen(
    onBack: () -> Unit,
    viewModel: HeartRateViewModel = viewModel(),
) {
    val context = LocalContext.current
    val appSettings = AppSettingsStore.settings
    var overlayEnabled by remember { mutableStateOf(Settings.canDrawOverlays(context)) }
    var batteryExempt by remember { mutableStateOf(context.isIgnoringBatteryOptimizations()) }

    val overlayPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        overlayEnabled = Settings.canDrawOverlays(context)
        if (overlayEnabled) viewModel.showOverlay()
    }
    val batteryPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        batteryExempt = context.isIgnoringBatteryOptimizations()
    }

    fun requestOverlay() {
        if (Settings.canDrawOverlays(context)) {
            overlayEnabled = true
            viewModel.showOverlay()
        } else {
            overlayPermissionLauncher.launch(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}")),
            )
        }
    }

    fun requestBatteryExemption() {
        if (!context.isIgnoringBatteryOptimizations()) {
            batteryPermissionLauncher.launch(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:${context.packageName}")
                },
            )
        }
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("Ajustes", fontWeight = FontWeight.Bold, textAlign = TextAlign.Center) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Volver")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                SettingSection(
                    title = "Alertas",
                    icon = Icons.Default.NotificationsActive,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Activar avisos de pulso", modifier = Modifier.weight(1f))
                        Switch(checked = appSettings.alertsEnabled, onCheckedChange = AppSettingsStore::setAlertsEnabled)
                    }
                    SettingSlider(
                        label = "Pulso bajo",
                        value = appSettings.lowAlert,
                        valueRange = 35f..120f,
                        enabled = appSettings.alertsEnabled,
                        onValueChange = AppSettingsStore::setLowAlert,
                    )
                    SettingSlider(
                        label = "Pulso alto",
                        value = appSettings.highAlert,
                        valueRange = 100f..220f,
                        enabled = appSettings.alertsEnabled,
                        onValueChange = AppSettingsStore::setHighAlert,
                    )
                }
            }
            item {
                SettingSection(
                    title = "Color del pulso",
                    icon = Icons.Default.Palette,
                ) {
                    val normalizedStops = appSettings.colorStops.normalizedColorStops()
                    val palette = listOf(
                        Color(0xFF2D9CDB),
                        Color(0xFF2ECC71),
                        Color(0xFFF2C94C),
                        Color(0xFFEB5757),
                        Color(0xFF9B51E0),
                        Color(0xFF00B8A9),
                        Color(0xFFFF7A59),
                        Color(0xFF1D3557),
                    )

                    PpmStepper(
                        label = "Escalas",
                        value = normalizedStops.size,
                        suffix = "",
                        min = 2,
                        max = 5,
                        step = 1,
                        onValueChange = { AppSettingsStore.setColorStops(resizeColorStops(normalizedStops, it)) },
                    )
                    normalizedStops.forEachIndexed { index, stop ->
                        val minPpm = if (index == 0) 35 else normalizedStops[index - 1].ppm + 1
                        val maxPpm = if (index == normalizedStops.lastIndex) 220 else normalizedStops[index + 1].ppm - 1
                        ColorStopEditor(
                            label = "Color ${index + 1}",
                            stop = stop,
                            minPpm = minPpm,
                            maxPpm = maxPpm,
                            onColorClick = {
                                AppSettingsStore.setColorStops(
                                    normalizedStops.replaceAt(index, stop.copy(color = nextColor(stop.color, palette))),
                                )
                            },
                            onPpmChange = {
                                AppSettingsStore.setColorStops(
                                    normalizedStops.replaceAt(index, stop.copy(ppm = it.coerceIn(minPpm, maxPpm))),
                                )
                            },
                        )
                    }
                }
            }
            item {
                SettingSection(
                    title = "Grafico",
                    icon = Icons.AutoMirrored.Filled.ShowChart,
                ) {
                    SettingSwitchRow("Cuadricula", appSettings.showChartGrid, AppSettingsStore::setShowChartGrid)
                    SettingSwitchRow("Marcar muestras", appSettings.showPointMarkers, AppSettingsStore::setShowPointMarkers)
                    SettingSwitchRow("Linea suavizada", appSettings.smoothChartLine, AppSettingsStore::setSmoothChartLine)
                    SettingSwitchRow("Trazo continuo", appSettings.continuousTrace, AppSettingsStore::setContinuousTrace)
                }
            }
            item {
                SettingSection(
                    title = "Monitor flotante",
                    icon = Icons.Default.PictureInPictureAlt,
                ) {
                    SettingSwitchRow(
                        title = "Widget universal",
                        checked = overlayEnabled,
                        onCheckedChange = { enabled ->
                            if (enabled) requestOverlay()
                            else {
                                overlayEnabled = false
                                viewModel.hideOverlay()
                            }
                        },
                    )
                    OutlinedButton(
                        onClick = ::requestBatteryExemption,
                        enabled = !batteryExempt,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            if (batteryExempt) "Bateria lista para monitoreo" else "Permitir toda la noche",
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingSwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(title, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun SettingSection(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    content: @Composable ColumnScope.() -> Unit,
) {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }
            content()
        }
    }
}

@Composable
private fun MonitorTab(
    currentBpm: Int,
    connectionState: String,
    discoveredDevices: List<BleDeviceInfo>,
    connectedDevice: BleDeviceInfo?,
    connectedName: String?,
    signalRssi: Int?,
    batteryLevel: Int?,
    entries: List<HeartRateEntry>,
    savedEntries: List<HeartRateEntry>,
    bpmColor: Color,
    alertState: String?,
    selectedRange: ChartRange,
    colorStops: List<ColorStopConfig>,
    appSettings: AppUiSettings,
    onSearch: () -> Unit,
    onDisconnect: () -> Unit,
    onDeviceSelected: (String) -> Unit,
    onAliasClick: () -> Unit,
    onRangeSelected: (ChartRange) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, top = 16.dp, end = 20.dp, bottom = 116.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        item {
            ConnectionPanel(
                connectionState = connectionState,
                connectedDevice = connectedDevice,
                connectedName = connectedName,
                signalRssi = signalRssi,
                batteryLevel = batteryLevel,
                onSearch = onSearch,
                onDisconnect = onDisconnect,
                onAliasClick = onAliasClick,
            )
        }

        if (connectedDevice == null) {
            if (discoveredDevices.isNotEmpty()) {
                item {
                    DeviceSection(discoveredDevices, onDeviceSelected)
                }
            }
        } else {
            item { PpmHero(currentBpm, bpmColor, alertState) }
            item {
                HeartRateChart(
                    entries = entries,
                    savedEntries = savedEntries,
                    bpmColor = bpmColor,
                    colorStops = colorStops,
                    appSettings = appSettings,
                    selectedRange = selectedRange,
                    onRangeSelected = onRangeSelected,
                )
            }
            item { SessionSummary(entries) }
        }
    }
}

@Composable
private fun ConnectionPanel(
    connectionState: String,
    connectedDevice: BleDeviceInfo?,
    connectedName: String?,
    signalRssi: Int?,
    batteryLevel: Int?,
    onSearch: () -> Unit,
    onDisconnect: () -> Unit,
    onAliasClick: () -> Unit,
) {
    val connected = connectedDevice != null
    if (connected) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(10.dp),
            color = MaterialTheme.colorScheme.primaryContainer,
            tonalElevation = 2.dp,
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(Icons.Default.Bluetooth, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                Text(
                    text = "Conectado a ${connectedName ?: connectedDevice.name}",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                signalRssi?.let {
                    Text("$it dBm", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.72f))
                }
                batteryLevel?.let {
                    Text("$it%", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.72f))
                }
                IconButton(onClick = onAliasClick, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Default.Edit, contentDescription = "Alias", modifier = Modifier.size(18.dp))
                }
                IconButton(onClick = onDisconnect, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Default.Close, contentDescription = "Desconectar", modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.error)
                }
            }
        }
        return
    }

    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Icon(
                imageVector = Icons.Default.BluetoothDisabled,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(30.dp),
            )
            Text(
                text = connectionState,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            Text(
                text = "Conecta un sensor BLE de pulso",
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.78f),
            )

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    onClick = onSearch,
                    modifier = Modifier.weight(1f).height(52.dp),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Icon(Icons.Default.Search, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Conectar", textAlign = TextAlign.Center)
                }
                OutlinedButton(
                    onClick = onDisconnect,
                    modifier = Modifier.weight(1f).height(52.dp),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Text("Detener")
                }
            }
        }
    }
}

@Composable
private fun InfoChip(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.height(44.dp),
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.64f),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(modifier = Modifier.width(4.dp))
            Text(text, style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun PpmHero(currentBpm: Int, bpmColor: Color, alertState: String?) {
    val beatDuration = (60000 / max(currentBpm, 48)).coerceIn(320, 1200)
    val transition = rememberInfiniteTransition(label = "heart-beat")
    val scale by transition.animateFloat(
        initialValue = 0.92f,
        targetValue = 1.12f,
        animationSpec = infiniteRepeatable(tween(beatDuration / 2), RepeatMode.Reverse),
        label = "heart-scale",
    )

    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.elevatedCardColors(containerColor = bpmColor.copy(alpha = 0.14f)),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(22.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Surface(shape = CircleShape, color = bpmColor.copy(alpha = 0.18f), modifier = Modifier.size(108.dp)) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        painterResource(R.drawable.ic_ecg_heart),
                        contentDescription = null,
                        tint = bpmColor,
                        modifier = Modifier.size(64.dp).scale(if (currentBpm > 0) scale else 1f),
                    )
                }
            }
            Text(
                text = if (currentBpm > 0) currentBpm.toString() else "--",
                fontSize = 78.sp,
                fontWeight = FontWeight.Black,
                lineHeight = 80.sp,
                textAlign = TextAlign.Center,
                color = bpmColor,
            )
            Text(
                text = alertState ?: "PPM en vivo",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
                color = if (alertState == null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun DeviceSection(devices: List<BleDeviceInfo>, onDeviceSelected: (String) -> Unit) {
    ElevatedCard(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(10.dp)) {
        Column(
            modifier = Modifier.padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("Sensores encontrados", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
            devices.take(6).forEach { device -> DeviceRow(device, onDeviceSelected) }
        }
    }
}

@Composable
private fun DeviceRow(device: BleDeviceInfo, onDeviceSelected: (String) -> Unit) {
    Surface(
        onClick = { onDeviceSelected(device.address) },
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(Icons.Default.Bluetooth, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.Start) {
                Text(device.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text("${device.address}  RSSI ${device.rssi}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
            }
            if (device.isHeartRateSensor) AssistChip(onClick = { onDeviceSelected(device.address) }, label = { Text("HR") })
        }
    }
}

@Composable
private fun HeartRateChart(
    entries: List<HeartRateEntry>,
    savedEntries: List<HeartRateEntry>,
    bpmColor: Color,
    colorStops: List<ColorStopConfig>,
    appSettings: AppUiSettings,
    selectedRange: ChartRange,
    onRangeSelected: (ChartRange) -> Unit,
) {
    var chartNow by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            chartNow = System.currentTimeMillis()
            delay(LIVE_CHART_FRAME_MS)
        }
    }

    val chartEntries = remember(entries, savedEntries) {
        (savedEntries + entries).distinctBy { it.timestamp }.sortedBy { it.timestamp }
    }
    val chartPoints = remember(chartEntries, selectedRange, chartNow, appSettings.continuousTrace) {
        chartEntries.toChartPoints(selectedRange, chartNow, appSettings.continuousTrace)
    }
    ElevatedCard(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(10.dp)) {
        Column(modifier = Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("Pulso en tiempo real", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(selectedRange.label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.outline)
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LiveChartRanges.forEach { range ->
                    RangePill(range, selectedRange == range, Modifier.weight(1f)) { onRangeSelected(range) }
                }
            }
            PpmCanvas(
                points = chartPoints,
                range = selectedRange,
                bpmColor = bpmColor,
                colorStops = colorStops,
                modifier = Modifier.fillMaxWidth().height(246.dp),
                breakGaps = true,
                showGrid = appSettings.showChartGrid,
                showPointMarkers = appSettings.showPointMarkers,
                smoothLine = appSettings.smoothChartLine,
            )
        }
    }
}

@Composable
private fun RangePill(range: ChartRange, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = modifier.height(46.dp),
        shape = RoundedCornerShape(12.dp),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                range.label,
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun CompactDayChart(
    points: List<ChartPoint>,
    bpmColor: Color,
    colorStops: List<ColorStopConfig>,
    appSettings: AppUiSettings,
    modifier: Modifier = Modifier,
) {
    PpmCanvas(
        points = points,
        range = ChartRange.OneDay,
        bpmColor = bpmColor,
        colorStops = colorStops,
        modifier = modifier,
        breakGaps = true,
        liveLabels = false,
        showGrid = appSettings.showChartGrid,
        showPointMarkers = appSettings.showPointMarkers,
        smoothLine = appSettings.smoothChartLine,
    )
}

@Composable
private fun PpmCanvas(
    points: List<ChartPoint>,
    range: ChartRange,
    bpmColor: Color,
    colorStops: List<ColorStopConfig>,
    modifier: Modifier,
    breakGaps: Boolean = true,
    liveLabels: Boolean = true,
    showGrid: Boolean = true,
    showPointMarkers: Boolean = true,
    smoothLine: Boolean = true,
) {
    Canvas(modifier = modifier) {
        val viewport = points.resolvePpmViewport()
        val minPpm = viewport.first
        val maxPpm = viewport.second
        val gridColor = Color.Gray.copy(alpha = 0.18f)
        val labelColor = Color.Gray.copy(alpha = 0.62f)
        val leftPad = 38.dp.toPx()
        val bottomPad = 24.dp.toPx()
        val chartWidth = size.width - leftPad
        val chartHeight = size.height - bottomPad
        val paint = android.graphics.Paint().apply {
            color = labelColor.toArgb()
            textSize = 10.sp.toPx()
        }

        for (line in 0..4) {
            val y = chartHeight * line / 4f
            val ppmLabel = (maxPpm - (maxPpm - minPpm) * line / 4f).roundToInt()
            if (showGrid) {
                drawLine(gridColor, Offset(leftPad, y), Offset(size.width, y), strokeWidth = 1.dp.toPx())
            }
            drawContext.canvas.nativeCanvas.drawText(ppmLabel.toString(), 0f, y + 4.dp.toPx(), paint)
        }

        val bucketSize = (range.durationMs / MAX_CHART_BUCKETS).coerceAtLeast(1L)
        val gapThreshold = max(bucketSize * 4, LIVE_CHART_GAP_THRESHOLD_MS)
        if (points.size > 1) {
            val path = Path()
            val fillPath = Path()
            var lastElapsed: Long? = null
            var segmentOpen = false
            var lastSegmentX = leftPad
            var previousX = leftPad
            var previousY = chartHeight
            points.forEachIndexed { index, point ->
                val normalized = ((point.bpm - minPpm) / (maxPpm - minPpm)).coerceIn(0f, 1f)
                val x = leftPad + chartWidth * (point.elapsedMs / range.durationMs.toFloat()).coerceIn(0f, 1f)
                val y = chartHeight - chartHeight * normalized
                val gap = lastElapsed?.let { point.elapsedMs - it } ?: 0L
                val shouldBreak = index == 0 || (breakGaps && gap > gapThreshold && !point.held)
                if (shouldBreak) {
                    if (segmentOpen) {
                        fillPath.lineTo(lastSegmentX, chartHeight)
                        fillPath.close()
                    }
                    path.moveTo(x, y)
                    fillPath.moveTo(x, chartHeight)
                    fillPath.lineTo(x, y)
                    segmentOpen = true
                } else {
                    val previousWasHeld = points.getOrNull(index - 1)?.held == true
                    if (smoothLine && !point.held && !previousWasHeld) {
                        path.quadraticTo(previousX, previousY, x, y)
                    } else {
                        path.lineTo(x, y)
                    }
                    fillPath.lineTo(x, y)
                }
                previousX = x
                previousY = y
                lastSegmentX = x
                lastElapsed = point.elapsedMs
            }
            if (segmentOpen) {
                val lastX = leftPad + chartWidth * (points.last().elapsedMs / range.durationMs.toFloat()).coerceIn(0f, 1f)
                fillPath.lineTo(lastX, chartHeight)
                fillPath.close()
            }
            drawPath(
                path = fillPath,
                brush = Brush.verticalGradient(
                    colors = listOf(
                        bpmColor.copy(alpha = 0.36f),
                        bpmColor.copy(alpha = 0.12f),
                        Color.Transparent,
                    ),
                    startY = 0f,
                    endY = chartHeight,
                ),
            )
            drawPath(path, bpmColor, style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
            if (showPointMarkers) {
                points.filterNot { it.held }.forEach { point ->
                    val normalized = ((point.bpm - minPpm) / (maxPpm - minPpm)).coerceIn(0f, 1f)
                    val x = leftPad + chartWidth * (point.elapsedMs / range.durationMs.toFloat()).coerceIn(0f, 1f)
                    val y = chartHeight - chartHeight * normalized
                    drawCircle(
                        color = ppmGradientColor(point.bpm, colorStops),
                        radius = 2.7.dp.toPx(),
                        center = Offset(x, y),
                    )
                }
            }
        } else if (points.size == 1) {
            val point = points.first()
            val normalized = ((point.bpm - minPpm) / (maxPpm - minPpm)).coerceIn(0f, 1f)
            val x = leftPad + chartWidth * (point.elapsedMs / range.durationMs.toFloat()).coerceIn(0f, 1f)
            val y = chartHeight - chartHeight * normalized
            drawCircle(color = bpmColor, radius = 4.dp.toPx(), center = Offset(x, y))
        }

        listOf(0f, 0.25f, 0.5f, 0.75f, 1f).forEach { fraction ->
            val x = leftPad + chartWidth * fraction
            val label = if (liveLabels) {
                formatLookbackLabel(range.durationMs * (1f - fraction))
            } else {
                (range.durationMs * fraction).formatElapsed()
            }
            val labelWidth = paint.measureText(label)
            val labelX = (x - labelWidth / 2f).coerceIn(leftPad, size.width - labelWidth)
            drawContext.canvas.nativeCanvas.drawText(label, labelX, size.height - 4.dp.toPx(), paint)
        }
    }
}

@Composable
private fun SessionSummary(entries: List<HeartRateEntry>) {
    val bpms = entries.map { it.bpm }
    val average = bpms.takeIf { it.isNotEmpty() }?.average()?.roundToInt() ?: 0
    val min = bpms.minOrNull() ?: 0
    val max = bpms.maxOrNull() ?: 0
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        StatTile("Prom", if (average > 0) "$average" else "--", Modifier.weight(1f))
        StatTile("Min", if (min > 0) "$min" else "--", Modifier.weight(1f))
        StatTile("Max", if (max > 0) "$max" else "--", Modifier.weight(1f))
    }
}

@Composable
private fun StatTile(label: String, value: String, modifier: Modifier = Modifier) {
    Surface(modifier = modifier.height(82.dp), shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
        Column(modifier = Modifier.padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.outline, textAlign = TextAlign.Center)
            Text(value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun HistoryTab(
    summaries: List<DailyHeartRateSummary>,
    colorStops: List<ColorStopConfig>,
    appSettings: AppUiSettings,
) {
    val context = LocalContext.current
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (summaries.isEmpty()) {
            item {
                EmptyState("Aun no hay sesiones guardadas", "Conecta un sensor y deja que PPM Stats junte datos del dia.")
            }
        } else {
            items(summaries) { summary ->
                DailySummaryCard(
                    summary = summary,
                    colorStops = colorStops,
                    appSettings = appSettings,
                    onClick = {
                        context.startActivity(
                            Intent(context, HistoryDetailActivity::class.java).putExtra(EXTRA_DAY_START, summary.dayStartMillis),
                        )
                    },
                )
            }
        }
    }
}

@Composable
private fun DailySummaryCard(
    summary: DailyHeartRateSummary,
    colorStops: List<ColorStopConfig>,
    appSettings: AppUiSettings,
    onClick: () -> Unit,
) {
    ElevatedCard(onClick = onClick, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(10.dp)) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(summary.label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
            Text("00:00 a 23:59 - ${summary.samples} muestras", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline, textAlign = TextAlign.Center)
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatTile("Min", "${summary.min}", Modifier.weight(1f))
                StatTile("Prom", "${summary.average}", Modifier.weight(1f))
                StatTile("Max", "${summary.max}", Modifier.weight(1f))
            }
            Text(
                text = if (appSettings.showPointMarkers) "Toca para ver el dia completo" else "Vista compacta del dia",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun DayDetailDialog(
    summary: DailyHeartRateSummary,
    entries: List<HeartRateEntry>,
    colorStops: List<ColorStopConfig>,
    onDismiss: () -> Unit,
) {
    val appSettings = AppSettingsStore.settings
    val points = remember(entries, summary.dayStartMillis, appSettings.continuousTrace) {
        entries.toDayChartPoints(summary.dayStartMillis, appSettings.continuousTrace)
    }
    val lineColor = ppmGradientColor(summary.average, colorStops)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(summary.label, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth()) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("00:00 a 23:59 - ${entries.size} muestras", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline, textAlign = TextAlign.Center)
                CompactDayChart(points, lineColor, colorStops, appSettings, Modifier.fillMaxWidth().height(220.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatTile("Min", "${summary.min}", Modifier.weight(1f))
                    StatTile("Prom", "${summary.average}", Modifier.weight(1f))
                    StatTile("Max", "${summary.max}", Modifier.weight(1f))
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Listo") } },
    )
}

@Composable
private fun MiniBar(label: String, value: Int, color: Color, modifier: Modifier = Modifier) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(modifier = Modifier.height(72.dp).fillMaxWidth(), contentAlignment = Alignment.BottomCenter) {
            val fraction = ((value - 40) / 160f).coerceIn(0.06f, 1f)
            Box(modifier = Modifier.width(18.dp).height((72 * fraction).dp).background(color.copy(alpha = 0.85f), RoundedCornerShape(8.dp)))
        }
        Text("$value", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline, textAlign = TextAlign.Center)
    }
}

class HistoryDetailActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        HeartRateMonitorRepository.initialize(this)
        AppSettingsStore.initialize(this)
        val dayStartMillis = intent.getLongExtra(EXTRA_DAY_START, currentDayStartMillis())
        setContent {
            BPMStatsTheme {
                HistoryDetailScreen(dayStartMillis = dayStartMillis, onBack = ::finish)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HistoryDetailScreen(dayStartMillis: Long, onBack: () -> Unit) {
    val entries by HeartRateMonitorRepository.savedEntries.collectAsState()
    val summaries by HeartRateMonitorRepository.dailySummaries.collectAsState()
    val dayEntries = remember(entries, dayStartMillis) { entries.filterForDay(dayStartMillis) }
    val summary = summaries.firstOrNull { it.dayStartMillis == dayStartMillis } ?: dayEntries.toDailySummary(dayStartMillis)
    val appSettings = AppSettingsStore.settings
    val colorStops = appSettings.colorStops
    val points = remember(dayEntries, dayStartMillis, appSettings.continuousTrace) {
        dayEntries.toDayChartPoints(dayStartMillis, appSettings.continuousTrace)
    }
    val lineColor = ppmGradientColor(summary.average, colorStops)

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(summary.label, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Volver")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            item {
                ElevatedCard(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(10.dp)) {
                    Column(modifier = Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        Text("00:00 a 23:59 - ${dayEntries.size} muestras", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline, textAlign = TextAlign.Center)
                        CompactDayChart(points, lineColor, colorStops, appSettings, Modifier.fillMaxWidth().height(260.dp))
                    }
                }
            }
            item {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatTile("Min", "${summary.min}", Modifier.weight(1f))
                    StatTile("Prom", "${summary.average}", Modifier.weight(1f))
                    StatTile("Max", "${summary.max}", Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun EmptyState(title: String, body: String) {
    ElevatedCard(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(10.dp)) {
        Column(modifier = Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(painterResource(R.drawable.ic_ecg_heart), contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(42.dp))
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
            Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.outline, textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun FullScreenMonitor(
    currentBpm: Int,
    entries: List<HeartRateEntry>,
    savedEntries: List<HeartRateEntry>,
    bpmColor: Color,
    alertState: String?,
    selectedRange: ChartRange,
    colorStops: List<ColorStopConfig>,
    appSettings: AppUiSettings,
    onExit: () -> Unit,
) {
    val view = LocalView.current
    val activity = remember(view) { view.context.findActivity() }
    DisposableEffect(activity, appSettings.keepScreenOnFullscreen) {
        if (appSettings.keepScreenOnFullscreen) {
            activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        onDispose {
            activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    var chartNow by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            chartNow = System.currentTimeMillis()
            delay(LIVE_CHART_FRAME_MS)
        }
    }
    val chartEntries = remember(entries, savedEntries) {
        (savedEntries + entries).distinctBy { it.timestamp }.sortedBy { it.timestamp }
    }
    val points = remember(chartEntries, selectedRange, chartNow, appSettings.continuousTrace) {
        chartEntries.toChartPoints(selectedRange, chartNow, appSettings.continuousTrace)
    }
    BoxWithConstraints(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        val horizontal = maxWidth > maxHeight
        Box(modifier = Modifier.fillMaxSize().padding(18.dp)) {
            PpmCanvas(
                points = points,
                range = selectedRange,
                bpmColor = bpmColor,
                colorStops = colorStops,
                modifier = Modifier.fillMaxSize(),
                showGrid = appSettings.showChartGrid,
                showPointMarkers = appSettings.showPointMarkers,
                smoothLine = appSettings.smoothChartLine,
            )
            if (horizontal) {
                Row(modifier = Modifier.align(Alignment.Center).fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                    PpmHero(currentBpm, bpmColor, alertState)
                }
            } else {
                Column(modifier = Modifier.align(Alignment.Center).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    PpmHero(currentBpm, bpmColor, alertState)
                }
            }
            IconButton(onClick = onExit, modifier = Modifier.align(Alignment.TopEnd)) {
                Icon(Icons.Default.Fullscreen, contentDescription = "Salir", tint = MaterialTheme.colorScheme.onBackground)
            }
            Surface(
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 8.dp),
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.86f),
                tonalElevation = 4.dp,
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text("Pantalla encendida", style = MaterialTheme.typography.labelLarge)
                    Switch(
                        checked = appSettings.keepScreenOnFullscreen,
                        onCheckedChange = AppSettingsStore::setKeepScreenOnFullscreen,
                    )
                }
            }
        }
    }
}

@Composable
private fun FloatingPulseWidget(bpm: Int, bpmColor: Color, alertState: String?, modifier: Modifier = Modifier) {
    val beatDuration = (60000 / max(bpm, 48)).coerceIn(320, 1200)
    val transition = rememberInfiniteTransition(label = "floating-heart")
    val scale by transition.animateFloat(
        initialValue = 0.96f,
        targetValue = 1.08f,
        animationSpec = infiniteRepeatable(tween(beatDuration / 2), RepeatMode.Reverse),
        label = "floating-heart-scale",
    )

    Surface(modifier = modifier.offset(y = (-4).dp), shape = RoundedCornerShape(10.dp), color = bpmColor, tonalElevation = 6.dp, shadowElevation = 8.dp) {
        Row(modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(painterResource(R.drawable.ic_ecg_heart), contentDescription = null, tint = Color.White, modifier = Modifier.size(22.dp).scale(if (bpm > 0) scale else 1f))
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(if (bpm > 0) "$bpm PPM" else "-- PPM", color = Color.White, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                Text(alertState ?: "En vivo", color = Color.White.copy(alpha = 0.82f), style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center)
            }
        }
    }
}

@Composable
private fun AliasDialog(device: BleDeviceInfo, initialAlias: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var alias by remember(device.address) { mutableStateOf(initialAlias) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Alias del sensor", textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth()) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(device.address, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline, textAlign = TextAlign.Center)
                OutlinedTextField(value = alias, onValueChange = { alias = it }, label = { Text("Nombre") }, singleLine = true)
            }
        },
        confirmButton = { TextButton(onClick = { onSave(alias) }) { Text("Guardar") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
}

@Composable
private fun AlertSettingsDialog(
    enabled: Boolean,
    lowAlert: Int,
    highAlert: Int,
    onEnabledChange: (Boolean) -> Unit,
    onLowChange: (Int) -> Unit,
    onHighChange: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Alertas", textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth()) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Activar alertas", modifier = Modifier.weight(1f), textAlign = TextAlign.Center)
                    Switch(checked = enabled, onCheckedChange = onEnabledChange)
                }
                SettingSlider("Bajo", lowAlert, 35f..120f, enabled, onLowChange)
                SettingSlider("Alto", highAlert, 100f..220f, enabled, onHighChange)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Listo") } },
    )
}

@Composable
private fun ColorSettingsDialog(
    stops: List<ColorStopConfig>,
    onStopsChange: (List<ColorStopConfig>) -> Unit,
    onDismiss: () -> Unit,
) {
    val palette = listOf(Color(0xFF2D9CDB), Color(0xFF2ECC71), Color(0xFFF2C94C), Color(0xFFEB5757), Color(0xFF9B51E0), Color(0xFF00B8A9), Color(0xFFFF7A59), Color(0xFF1D3557))
    val normalizedStops = stops.normalizedColorStops()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Colores de pulso", textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth()) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                PpmStepper(
                    label = "Colores",
                    value = normalizedStops.size,
                    suffix = "",
                    min = 2,
                    max = 5,
                    step = 1,
                    onValueChange = { onStopsChange(resizeColorStops(normalizedStops, it)) },
                )
                normalizedStops.forEachIndexed { index, stop ->
                    val minPpm = if (index == 0) 35 else normalizedStops[index - 1].ppm + 1
                    val maxPpm = if (index == normalizedStops.lastIndex) 220 else normalizedStops[index + 1].ppm - 1
                    ColorStopEditor(
                        label = "Color ${index + 1}",
                        stop = stop,
                        minPpm = minPpm,
                        maxPpm = maxPpm,
                        onColorClick = {
                            onStopsChange(normalizedStops.replaceAt(index, stop.copy(color = nextColor(stop.color, palette))))
                        },
                        onPpmChange = {
                            onStopsChange(normalizedStops.replaceAt(index, stop.copy(ppm = it.coerceIn(minPpm, maxPpm))).normalizedColorStops())
                        },
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Listo") } },
    )
}

@Composable
private fun SettingSlider(label: String, value: Int, valueRange: ClosedFloatingPointRange<Float>, enabled: Boolean, onValueChange: (Int) -> Unit, suffix: String = " PPM") {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center)
            Text("$value$suffix", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
        }
        Slider(value = value.toFloat(), onValueChange = { onValueChange(it.roundToInt()) }, valueRange = valueRange, enabled = enabled)
    }
}

@Composable
private fun ColorStopEditor(
    label: String,
    stop: ColorStopConfig,
    minPpm: Int,
    maxPpm: Int,
    onColorClick: () -> Unit,
    onPpmChange: (Int) -> Unit,
) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(modifier = Modifier.size(42.dp).background(stop.color, CircleShape).clickable(onClick = onColorClick))
        PpmStepper(
            label = label,
            value = stop.ppm,
            min = minPpm,
            max = maxPpm,
            step = 1,
            onValueChange = onPpmChange,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun PpmStepper(
    label: String,
    value: Int,
    min: Int,
    max: Int,
    step: Int,
    onValueChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
    suffix: String = " PPM",
) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { onValueChange((value - step).coerceAtLeast(min)) }, enabled = value > min, shape = RoundedCornerShape(12.dp), contentPadding = PaddingValues(horizontal = 10.dp)) {
                Text("-", fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
            }
            Surface(shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.weight(1f, fill = false)) {
                Text("$value$suffix", modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
            }
            OutlinedButton(onClick = { onValueChange((value + step).coerceAtMost(max)) }, enabled = value < max, shape = RoundedCornerShape(12.dp), contentPadding = PaddingValues(horizontal = 10.dp)) {
                Text("+", fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
            }
        }
    }
}

private fun ppmGradientColor(bpm: Int, stops: List<ColorStopConfig>): Color {
    if (bpm <= 0) return Color(0xFF8A8F98)
    val normalized = stops.normalizedColorStops()
    if (bpm <= normalized.first().ppm) return normalized.first().color
    if (bpm >= normalized.last().ppm) return normalized.last().color

    val nextIndex = normalized.indexOfFirst { bpm <= it.ppm }.coerceAtLeast(1)
    val start = normalized[nextIndex - 1]
    val end = normalized[nextIndex]
    val fraction = ((bpm - start.ppm) / (end.ppm - start.ppm).toFloat()).coerceIn(0f, 1f)
    return lerp(start.color, end.color, fraction)
}

private fun defaultColorStops(count: Int): List<ColorStopConfig> {
    val defaults = listOf(
        ColorStopConfig(50, Color(0xFF2D9CDB)),
        ColorStopConfig(75, Color(0xFF00B8A9)),
        ColorStopConfig(105, Color(0xFF2ECC71)),
        ColorStopConfig(145, Color(0xFFF2C94C)),
        ColorStopConfig(180, Color(0xFFEB5757)),
    )
    return when (count.coerceIn(2, 5)) {
        2 -> listOf(defaults.first(), defaults.last())
        3 -> listOf(defaults[0], defaults[2], defaults[4])
        4 -> listOf(defaults[0], defaults[2], defaults[3], defaults[4])
        else -> defaults
    }
}

private fun resizeColorStops(current: List<ColorStopConfig>, count: Int): List<ColorStopConfig> {
    val safeCount = count.coerceIn(2, 5)
    if (current.size == safeCount) return current.normalizedColorStops()
    val defaults = defaultColorStops(safeCount)
    return if (current.size > safeCount) {
        current.normalizedColorStops().take(safeCount)
    } else {
        (current.normalizedColorStops() + defaults.drop(current.size)).take(safeCount).normalizedColorStops()
    }
}

private fun List<ColorStopConfig>.normalizedColorStops(): List<ColorStopConfig> {
    return sortedBy { it.ppm }
        .take(5)
        .ifEmpty { defaultColorStops(4) }
        .let { if (it.size < 2) defaultColorStops(2) else it }
}

private fun List<ColorStopConfig>.replaceAt(index: Int, value: ColorStopConfig): List<ColorStopConfig> {
    return mapIndexed { currentIndex, current -> if (currentIndex == index) value else current }
}

private fun List<HeartRateEntry>.toChartPoints(
    range: ChartRange,
    now: Long = System.currentTimeMillis(),
    continuousTrace: Boolean = false,
): List<ChartPoint> {
    val start = now - range.durationMs
    val bucketSize = (range.durationMs / MAX_CHART_BUCKETS).coerceAtLeast(1L)
    val sorted = sortedBy { it.timestamp }
    var points = sorted.asSequence()
        .filter { it.timestamp in start..now }
        .groupBy { ((it.timestamp - start) / bucketSize).coerceIn(0, MAX_CHART_BUCKETS - 1L) }
        .toSortedMap()
        .map { (_, entries) ->
            ChartPoint(entries.map { it.bpm }.average().roundToInt(), entries.last().timestamp - start)
        }
        .toMutableList()

    if (continuousTrace) {
        val sampleAtWindowStart = sorted.lastOrNull { it.timestamp <= start }
        if (
            sampleAtWindowStart != null &&
            now - sampleAtWindowStart.timestamp <= range.durationMs + CONTINUOUS_TRACE_STALE_MARGIN_MS &&
            (points.isEmpty() || points.first().elapsedMs > 0L)
        ) {
            points.add(0, ChartPoint(sampleAtWindowStart.bpm, 0L, held = true))
        }

        points = points.withContinuousHolds(LIVE_CHART_FRAME_MS).toMutableList()
        val lastSample = sorted.lastOrNull { it.timestamp <= now }
        if (lastSample != null && now - lastSample.timestamp <= range.durationMs + CONTINUOUS_TRACE_STALE_MARGIN_MS) {
            if (points.isEmpty()) {
                points += ChartPoint(lastSample.bpm, 0L, held = true)
            }

            val lastPoint = points.lastOrNull()
            if (lastPoint == null || lastPoint.elapsedMs < range.durationMs - LIVE_CHART_FRAME_MS) {
                points += ChartPoint(lastSample.bpm, range.durationMs, held = true)
            }
        }
    }

    return points.sortedBy { it.elapsedMs }
}

private fun currentDayStartMillis(): Long {
    val zoneId = ZoneId.systemDefault()
    return LocalDate.now(zoneId).atStartOfDay(zoneId).toInstant().toEpochMilli()
}

private fun List<ChartPoint>.resolvePpmViewport(): Pair<Float, Float> {
    if (isEmpty()) return 40f to 200f

    val latest = last().bpm
    val minValue = minOf { it.bpm }
    val maxValue = maxOf { it.bpm }
    val paddedMin = minOf(minValue, latest) - 18
    val paddedMax = maxOf(maxValue, latest) + 18
    val minVisibleRange = 48
    val center = latest
    val dynamicMin = if (paddedMax - paddedMin < minVisibleRange) center - minVisibleRange / 2 else paddedMin
    val dynamicMax = if (paddedMax - paddedMin < minVisibleRange) center + minVisibleRange / 2 else paddedMax
    var lower = dynamicMin.coerceAtLeast(30)
    var upper = dynamicMax.coerceAtMost(230)
    if (upper - lower < minVisibleRange) {
        upper = (lower + minVisibleRange).coerceAtMost(230)
        lower = (upper - minVisibleRange).coerceAtLeast(30)
    }
    return lower.toFloat() to upper.toFloat()
}

private fun List<HeartRateEntry>.filterForDay(dayStartMillis: Long): List<HeartRateEntry> {
    val dayEndMillis = dayStartMillis + ChartRange.OneDay.durationMs
    return filter { it.timestamp in dayStartMillis until dayEndMillis }.sortedBy { it.timestamp }
}

private fun List<HeartRateEntry>.toDayChartPoints(
    dayStartMillis: Long,
    continuousTrace: Boolean = false,
): List<ChartPoint> {
    val bucketSize = (ChartRange.OneDay.durationMs / MAX_CHART_BUCKETS).coerceAtLeast(1L)
    val points = filterForDay(dayStartMillis)
        .groupBy { ((it.timestamp - dayStartMillis) / bucketSize).coerceIn(0, MAX_CHART_BUCKETS - 1L) }
        .toSortedMap()
        .map { (_, entries) ->
            ChartPoint(entries.map { it.bpm }.average().roundToInt(), entries.last().timestamp - dayStartMillis)
        }
    return if (continuousTrace) points.withContinuousHolds(bucketSize) else points
}

private fun List<ChartPoint>.withContinuousHolds(minGapMs: Long): List<ChartPoint> {
    if (size < 2) return this
    return buildList {
        this@withContinuousHolds.forEachIndexed { index, point ->
            if (index > 0) {
                val previous = this@withContinuousHolds[index - 1]
                if (point.elapsedMs - previous.elapsedMs > minGapMs) {
                    add(ChartPoint(previous.bpm, point.elapsedMs, held = true))
                }
            }
            add(point)
        }
    }
}

private fun List<HeartRateEntry>.toDailySummary(dayStartMillis: Long): DailyHeartRateSummary {
    val zoneId = ZoneId.systemDefault()
    val date = Instant.ofEpochMilli(dayStartMillis).atZone(zoneId).toLocalDate()
    val bpms = map { it.bpm }
    return DailyHeartRateSummary(
        dayStartMillis = dayStartMillis,
        label = date.format(DateTimeFormatter.ofPattern("dd MMM yyyy")),
        samples = size,
        average = bpms.takeIf { it.isNotEmpty() }?.average()?.roundToInt() ?: 0,
        min = bpms.minOrNull() ?: 0,
        max = bpms.maxOrNull() ?: 0,
    )
}

private fun Float.formatElapsed(): String = toLong().formatElapsed()

private fun Long.formatElapsed(): String {
    val totalSeconds = (this / 1000L).coerceAtLeast(0L)
    val hours = totalSeconds / 3600L
    val minutes = (totalSeconds % 3600L) / 60L
    return if (hours > 0) "${hours}h ${minutes}m" else "${minutes}m"
}

private fun formatLookbackLabel(milliseconds: Float): String {
    val totalSeconds = (milliseconds / 1000f).roundToInt().coerceAtLeast(0)
    if (totalSeconds <= 2) return "ahora"
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return when {
        hours > 0 && minutes > 0 -> "-${hours}h ${minutes}m"
        hours > 0 -> "-${hours}h"
        minutes > 0 -> "-${minutes}m"
        else -> "-${seconds}s"
    }
}

private fun nextColor(current: Color, palette: List<Color>): Color {
    val index = palette.indexOfFirst { it.value == current.value }
    return palette[(index + 1).floorMod(palette.size)]
}

private fun Int.floorMod(other: Int): Int = ((this % other) + other) % other

private fun requiredBlePermissions(): Array<String> {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
    } else {
        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }
}

private fun Context.hasBlePermissions(): Boolean = requiredBlePermissions().all(::hasPermission)

private fun Context.hasPermission(permission: String): Boolean {
    return ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
}

private fun Context.isBluetoothEnabled(): Boolean {
    val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    return bluetoothManager.adapter?.isEnabled == true
}

private fun Context.isIgnoringBatteryOptimizations(): Boolean {
    val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
    return powerManager.isIgnoringBatteryOptimizations(packageName)
}

private tailrec fun Context.findActivity(): Activity? {
    return when (this) {
        is Activity -> this
        is android.content.ContextWrapper -> baseContext.findActivity()
        else -> null
    }
}

private const val MAX_CHART_BUCKETS = 180L
private const val LIVE_CHART_FRAME_MS = 250L
private const val LIVE_CHART_GAP_THRESHOLD_MS = 20_000L
private const val CONTINUOUS_TRACE_STALE_MARGIN_MS = 2 * 60 * 1000L
private const val EXTRA_DAY_START = "extra_day_start"
