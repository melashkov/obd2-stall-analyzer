package com.melashkov.obdstallanalyzer.domain.session

import com.melashkov.obdstallanalyzer.domain.capture.CaptureState
import com.melashkov.obdstallanalyzer.domain.model.ObdSample
import com.melashkov.obdstallanalyzer.domain.repository.ObdDevice
import kotlinx.coroutines.flow.StateFlow

internal enum class SessionStatusKind { IDLE, WORKING, CONNECTED, ERROR }

internal data class ObdSessionState(
    val status: String = "Not connected",
    val statusKind: SessionStatusKind = SessionStatusKind.IDLE,
    val sample: ObdSample? = null,
    val sessionActive: Boolean = false,
    val captureState: CaptureState = CaptureState(),
)

internal interface ObdSessionController {
    val state: StateFlow<ObdSessionState>
    val isAvailable: Boolean
    val isEnabled: Boolean

    fun pairedDevices(): List<ObdDevice>
    fun diagnosticLog(): String
    fun analysisData(): String
}
