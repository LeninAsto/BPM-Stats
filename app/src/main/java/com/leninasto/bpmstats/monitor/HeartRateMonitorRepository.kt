package com.leninasto.bpmstats.monitor

import android.content.Context
import android.content.SharedPreferences
import com.leninasto.bpmstats.ble.BleDeviceInfo
import com.leninasto.bpmstats.ble.BleHeartRateManager
import com.leninasto.bpmstats.data.AppDatabase
import com.leninasto.bpmstats.data.HeartRateEntry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.abs
import kotlin.math.roundToInt

data class DailyHeartRateSummary(
    val dayStartMillis: Long,
    val label: String,
    val samples: Int,
    val average: Int,
    val min: Int,
    val max: Int,
)

object HeartRateMonitorRepository {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private lateinit var bleManager: BleHeartRateManager
    private lateinit var database: AppDatabase
    private lateinit var preferences: SharedPreferences

    private var initialized = false
    private var lastSavedAt = 0L
    private var lastSavedBpm = 0
    private var activeAddress: String? = null
    private var sampleIntervalMs = BASE_SAVE_INTERVAL_MS

    private val _sessionEntries = MutableStateFlow<List<HeartRateEntry>>(emptyList())
    val sessionEntries: StateFlow<List<HeartRateEntry>> = _sessionEntries.asStateFlow()

    private val _savedEntries = MutableStateFlow<List<HeartRateEntry>>(emptyList())
    val savedEntries: StateFlow<List<HeartRateEntry>> = _savedEntries.asStateFlow()

    private val _dailySummaries = MutableStateFlow<List<DailyHeartRateSummary>>(emptyList())
    val dailySummaries: StateFlow<List<DailyHeartRateSummary>> = _dailySummaries.asStateFlow()

    private val _deviceAliases = MutableStateFlow<Map<String, String>>(emptyMap())
    val deviceAliases: StateFlow<Map<String, String>> = _deviceAliases.asStateFlow()

    private val _sampleIntervalSeconds = MutableStateFlow((BASE_SAVE_INTERVAL_MS / 1000L).toInt())
    val sampleIntervalSeconds: StateFlow<Int> = _sampleIntervalSeconds.asStateFlow()

    val currentBpm: StateFlow<Int>
        get() = bleManager.heartRate

    val connectionState: StateFlow<String>
        get() = bleManager.connectionState

    val discoveredDevices: StateFlow<List<BleDeviceInfo>>
        get() = bleManager.discoveredDevices

    val connectedDevice: StateFlow<BleDeviceInfo?>
        get() = bleManager.connectedDevice

    val signalRssi: StateFlow<Int?>
        get() = bleManager.signalRssi

    val batteryLevel: StateFlow<Int?>
        get() = bleManager.batteryLevel

    fun initialize(context: Context) {
        if (initialized) return

        val appContext = context.applicationContext
        database = AppDatabase.getDatabase(appContext)
        bleManager = BleHeartRateManager(appContext)
        preferences = appContext.getSharedPreferences("ppm_stats_settings", Context.MODE_PRIVATE)
        sampleIntervalMs = preferences.getLong(KEY_SAMPLE_INTERVAL_MS, BASE_SAVE_INTERVAL_MS)
        _sampleIntervalSeconds.value = (sampleIntervalMs / 1000L).toInt()
        _deviceAliases.value = preferences.all
            .filterKeys { it.startsWith(KEY_ALIAS_PREFIX) }
            .mapKeys { it.key.removePrefix(KEY_ALIAS_PREFIX) }
            .mapValues { it.value.toString() }
        initialized = true

        scope.launch {
            bleManager.heartRate.collect { bpm ->
                if (bpm > 0 && bleManager.connectionState.value == "Conectado") {
                    saveSampleIfUseful(bpm)
                }
            }
        }

        scope.launch {
            database.heartRateDao().getAllEntries().collect { entries ->
                _savedEntries.value = entries
                _dailySummaries.value = entries.toDailySummaries()
            }
        }
    }

    fun startScan() {
        bleManager.startScan()
    }

    fun connectToDevice(address: String) {
        activeAddress = address
        lastSavedAt = 0L
        lastSavedBpm = 0
        _sessionEntries.value = emptyList()
        bleManager.connectToDevice(address)
    }

    fun reconnectActiveDevice() {
        activeAddress?.let(::connectToDevice)
    }

    fun disconnect() {
        activeAddress = null
        bleManager.disconnect()
    }

    fun setDeviceAlias(address: String, alias: String) {
        val cleanAlias = alias.trim()
        val current = _deviceAliases.value.toMutableMap()
        if (cleanAlias.isBlank()) {
            current.remove(address)
            preferences.edit().remove(KEY_ALIAS_PREFIX + address).apply()
        } else {
            current[address] = cleanAlias
            preferences.edit().putString(KEY_ALIAS_PREFIX + address, cleanAlias).apply()
        }
        _deviceAliases.value = current
    }

    fun setSampleIntervalSeconds(seconds: Int) {
        val safeSeconds = seconds.coerceIn(2, 60)
        sampleIntervalMs = safeSeconds * 1000L
        _sampleIntervalSeconds.value = safeSeconds
        preferences.edit().putLong(KEY_SAMPLE_INTERVAL_MS, sampleIntervalMs).apply()
    }

    private suspend fun saveSampleIfUseful(bpm: Int) {
        val now = System.currentTimeMillis()
        val elapsed = now - lastSavedAt
        val changedEnough = abs(bpm - lastSavedBpm) >= MIN_BPM_CHANGE_FOR_FAST_SAMPLE
        val shouldSave = elapsed >= sampleIntervalMs || (elapsed >= FAST_SAVE_INTERVAL_MS && changedEnough)

        if (!shouldSave) return

        val entry = HeartRateEntry(bpm = bpm, timestamp = now)
        database.heartRateDao().insert(entry)
        _sessionEntries.value = (_sessionEntries.value + entry).takeLast(MAX_SESSION_POINTS)
        lastSavedAt = now
        lastSavedBpm = bpm
    }

    private const val MAX_SESSION_POINTS = 1_440
    private const val BASE_SAVE_INTERVAL_MS = 10_000L
    private const val FAST_SAVE_INTERVAL_MS = 2_000L
    private const val MIN_BPM_CHANGE_FOR_FAST_SAMPLE = 3
    private const val KEY_SAMPLE_INTERVAL_MS = "sample_interval_ms"
    private const val KEY_ALIAS_PREFIX = "alias_"
}

private fun List<HeartRateEntry>.toDailySummaries(): List<DailyHeartRateSummary> {
    val zoneId = ZoneId.systemDefault()
    val formatter = DateTimeFormatter.ofPattern("dd MMM yyyy")

    return groupBy { entry ->
        Instant.ofEpochMilli(entry.timestamp)
            .atZone(zoneId)
            .toLocalDate()
    }
        .toSortedMap(compareByDescending { it })
        .map { (date, entries) ->
            val bpms = entries.map { it.bpm }
            DailyHeartRateSummary(
                dayStartMillis = date.atStartOfDay(zoneId).toInstant().toEpochMilli(),
                label = date.format(formatter),
                samples = entries.size,
                average = bpms.average().roundToInt(),
                min = bpms.minOrNull() ?: 0,
                max = bpms.maxOrNull() ?: 0,
            )
        }
}
