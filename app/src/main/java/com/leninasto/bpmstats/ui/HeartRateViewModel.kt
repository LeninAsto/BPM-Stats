package com.leninasto.bpmstats.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.leninasto.bpmstats.ble.BleDeviceInfo
import com.leninasto.bpmstats.data.HeartRateEntry
import com.leninasto.bpmstats.monitor.DailyHeartRateSummary
import com.leninasto.bpmstats.monitor.HeartRateMonitorRepository
import com.leninasto.bpmstats.monitor.HeartRateMonitorService
import com.leninasto.bpmstats.monitor.PulseOverlayService
import kotlinx.coroutines.flow.StateFlow

class HeartRateViewModel(application: Application) : AndroidViewModel(application) {

    private val appContext = application.applicationContext

    init {
        HeartRateMonitorRepository.initialize(appContext)
    }

    val currentBpm: StateFlow<Int> = HeartRateMonitorRepository.currentBpm
    val connectionState: StateFlow<String> = HeartRateMonitorRepository.connectionState
    val discoveredDevices: StateFlow<List<BleDeviceInfo>> = HeartRateMonitorRepository.discoveredDevices
    val connectedDevice: StateFlow<BleDeviceInfo?> = HeartRateMonitorRepository.connectedDevice
    val signalRssi: StateFlow<Int?> = HeartRateMonitorRepository.signalRssi
    val batteryLevel: StateFlow<Int?> = HeartRateMonitorRepository.batteryLevel
    val sessionEntries: StateFlow<List<HeartRateEntry>> = HeartRateMonitorRepository.sessionEntries
    val savedEntries: StateFlow<List<HeartRateEntry>> = HeartRateMonitorRepository.savedEntries
    val dailySummaries: StateFlow<List<DailyHeartRateSummary>> = HeartRateMonitorRepository.dailySummaries
    val deviceAliases: StateFlow<Map<String, String>> = HeartRateMonitorRepository.deviceAliases
    val sampleIntervalSeconds: StateFlow<Int> = HeartRateMonitorRepository.sampleIntervalSeconds


    fun startTracking() {
        HeartRateMonitorRepository.startScan()
    }

    fun connectToDevice(address: String) {
        HeartRateMonitorService.start(appContext, address)
    }

    fun stopTracking() {
        HeartRateMonitorService.stop(appContext)
    }

    fun showOverlay() {
        PulseOverlayService.start(appContext)
    }

    fun hideOverlay() {
        PulseOverlayService.stop(appContext)
    }

    fun setDeviceAlias(address: String, alias: String) {
        HeartRateMonitorRepository.setDeviceAlias(address, alias)
    }

    fun setSampleIntervalSeconds(seconds: Int) {
        HeartRateMonitorRepository.setSampleIntervalSeconds(seconds)
    }

    override fun onCleared() {
        super.onCleared()
    }
}
