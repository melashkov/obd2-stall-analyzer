package com.melashkov.obdstallanalyzer.domain.repository

import com.melashkov.obdstallanalyzer.domain.capture.CaptureState
import com.melashkov.obdstallanalyzer.domain.model.ObdSample
import kotlinx.coroutines.flow.Flow

internal data class ObdDevice(
    val id: String,
    val name: String,
)

internal sealed interface ObdEvent {
    data class Status(val message: String) : ObdEvent
    data class Connected(val deviceName: String) : ObdEvent
    data class SampleReceived(
        val sample: ObdSample,
        val captureState: CaptureState,
    ) : ObdEvent
    data class Failed(val message: String) : ObdEvent
    data object Disconnected : ObdEvent
}

/** Domain-facing boundary for an OBD data source. */
internal interface ObdRepository {
    val isAvailable: Boolean
    val isEnabled: Boolean

    fun pairedDevices(): List<ObdDevice>
    fun observe(deviceId: String): Flow<ObdEvent>
    fun diagnosticLog(): String
    fun analysisData(): String
    fun disconnect()
}
