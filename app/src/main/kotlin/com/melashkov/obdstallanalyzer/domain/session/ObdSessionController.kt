package com.melashkov.obdstallanalyzer.domain.session

import com.melashkov.obdstallanalyzer.domain.model.ObdSample
import com.melashkov.obdstallanalyzer.domain.repository.ObdDevice
import kotlinx.coroutines.flow.StateFlow

internal enum class SessionStatusKind { IDLE, WORKING, CONNECTED, ERROR }

internal enum class CapturePhase { WAITING, ARMED, CAPTURING, READY }

internal data class ObdSessionState(
    val status: String = "Not connected",
    val statusKind: SessionStatusKind = SessionStatusKind.IDLE,
    val sample: ObdSample? = null,
    val sessionActive: Boolean = false,
    val capturePhase: CapturePhase = CapturePhase.WAITING,
    val captureSecondsRemaining: Long = 0L,
)

internal interface ObdSessionController {
    val state: StateFlow<ObdSessionState>
    val isAvailable: Boolean
    val isEnabled: Boolean

    fun pairedDevices(): List<ObdDevice>
    fun diagnosticLog(): String
    fun analysisData(): String
}
