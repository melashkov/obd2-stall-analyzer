package com.melashkov.obdstallanalyzer.presentation

import com.melashkov.obdstallanalyzer.domain.model.ObdSample
import com.melashkov.obdstallanalyzer.domain.repository.ObdDevice

internal enum class UiTone { MUTED, ACCENT, WARNING, ERROR }

internal data class DashboardUiState(
    val status: String = "Not connected",
    val statusTone: UiTone = UiTone.MUTED,
    val sample: ObdSample? = null,
    val captureStatus: String = "Waiting for engine start · retains 60 s before and 10 s after",
    val captureTone: UiTone = UiTone.MUTED,
    val sessionActive: Boolean = false,
    val demoRunning: Boolean = false,
    val pairedDevices: List<ObdDevice>? = null,
    val diagnostics: String? = null,
    val message: String? = null,
)

internal sealed interface UiEffect {
    data object RequestBluetoothPermissions : UiEffect
    data object OpenBluetoothSettings : UiEffect
    data class StartRecording(val deviceId: String) : UiEffect
    data object StopRecording : UiEffect
    data class Share(val text: String) : UiEffect
    data class Copy(val text: String) : UiEffect
}
