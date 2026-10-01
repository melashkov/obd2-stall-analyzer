package com.melashkov.obdstallanalyzer.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.melashkov.obdstallanalyzer.domain.capture.StallEventRecorder
import com.melashkov.obdstallanalyzer.domain.model.ObdSample
import com.melashkov.obdstallanalyzer.domain.report.AiDiagnosticReport
import com.melashkov.obdstallanalyzer.domain.repository.ObdDevice
import com.melashkov.obdstallanalyzer.domain.session.CapturePhase
import com.melashkov.obdstallanalyzer.domain.session.ObdSessionController
import com.melashkov.obdstallanalyzer.domain.session.ObdSessionState
import com.melashkov.obdstallanalyzer.domain.session.SessionStatusKind
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.sin

internal class StallAnalyzerViewModel : ViewModel() {
    private val mutableUiState = MutableStateFlow(DashboardUiState())
    val uiState: StateFlow<DashboardUiState> = mutableUiState.asStateFlow()

    private val effectChannel = Channel<UiEffect>(Channel.BUFFERED)
    val effects = effectChannel.receiveAsFlow()

    private var controller: ObdSessionController? = null
    private var sessionStateJob: Job? = null
    private var demoJob: Job? = null
    private var captureUiUntil = 0L
    private var captureReady = false

    fun attachController(controller: ObdSessionController) {
        if (this.controller === controller) return
        sessionStateJob?.cancel()
        this.controller = controller
        sessionStateJob = viewModelScope.launch {
            controller.state.collect(::applySessionState)
        }
    }

    fun detachController(controller: ObdSessionController?) {
        if (this.controller !== controller) return
        sessionStateJob?.cancel()
        sessionStateJob = null
        this.controller = null
    }

    fun onConnectClicked(hasBluetoothPermission: Boolean) {
        if (mutableUiState.value.sessionActive) {
            requestStopRecording("Disconnected")
            return
        }
        stopDemo(clearSample = true)
        val controller = controller
        when {
            controller == null -> showMessage("The recorder service is starting. Try again in a moment.")
            !controller.isAvailable -> showMessage("Bluetooth is not available on this device.")
            !hasBluetoothPermission -> emit(UiEffect.RequestBluetoothPermissions)
            !controller.isEnabled -> {
                showMessage("Enable Bluetooth and pair your adapter, then return here.")
                emit(UiEffect.OpenBluetoothSettings)
            }
            else -> loadPairedDevices()
        }
    }

    fun onBluetoothPermissionResult(granted: Boolean) {
        if (granted) onConnectClicked(hasBluetoothPermission = true)
        else showMessage("Nearby-device Bluetooth scan and connect permissions are required.")
    }

    fun loadPairedDevices() {
        val controller = controller ?: return showMessage("The recorder service is not available.")
        runCatching { controller.pairedDevices() }
            .onSuccess { devices ->
                if (devices.isEmpty()) {
                    showMessage(
                        "No paired adapters found. Pair a Bluetooth Classic ELM327, OBDLink " +
                            "or vLinker adapter in Android Bluetooth settings first.",
                    )
                } else {
                    mutableUiState.update { it.copy(pairedDevices = devices) }
                }
            }
            .onFailure { showMessage(it.message ?: "Unable to read paired Bluetooth devices.") }
    }

    fun dismissDevicePicker() {
        mutableUiState.update { it.copy(pairedDevices = null) }
    }

    fun connect(device: ObdDevice) {
        dismissDevicePicker()
        stopDemo(clearSample = true)
        clearCaptureState()
        mutableUiState.update {
            it.copy(
                status = "Connecting to ${device.name}…",
                statusTone = UiTone.WARNING,
                sample = null,
                sessionActive = true,
            )
        }
        emit(UiEffect.StartRecording(device.id))
    }

    fun toggleDemo() {
        if (mutableUiState.value.demoRunning) {
            stopDemo(clearSample = true)
            mutableUiState.update { it.copy(status = "Not connected", statusTone = UiTone.MUTED) }
            return
        }
        if (mutableUiState.value.sessionActive) emit(UiEffect.StopRecording)
        clearCaptureState()
        mutableUiState.update {
            it.copy(
                status = "Demo data — no ECU connection",
                statusTone = UiTone.WARNING,
                sample = null,
                sessionActive = false,
                demoRunning = true,
            )
        }
        demoJob = viewModelScope.launch {
            val startedAt = System.currentTimeMillis()
            var stallEmitted = false
            while (isActive) {
                val seconds = (System.currentTimeMillis() - startedAt) / 1000.0f
                val sample = when {
                    seconds < DEMO_ENGINE_START_SECONDS -> demoSample(seconds).copy(
                        rpm = 0.0f,
                        fuelSystemStatus = "Waiting for engine",
                    )
                    seconds < DEMO_STALL_SECONDS ->
                        demoSample(seconds - DEMO_ENGINE_START_SECONDS)
                    else -> demoSample(seconds - DEMO_ENGINE_START_SECONDS).copy(
                        rpm = 0.0f,
                        event = if (stallEmitted) "" else StallEventRecorder.STALL_EVENT,
                    )
                }
                if (sample.event == StallEventRecorder.STALL_EVENT) stallEmitted = true
                updateSample(sample)
                delay(350L)
            }
        }
    }

    fun showDiagnostics() {
        mutableUiState.update {
            it.copy(diagnostics = controller?.diagnosticLog().orEmpty())
        }
    }

    fun dismissDiagnostics() {
        mutableUiState.update { it.copy(diagnostics = null) }
    }

    fun copyDiagnostics() {
        val diagnostics = mutableUiState.value.diagnostics ?: controller?.diagnosticLog().orEmpty()
        emit(UiEffect.Copy(diagnostics))
    }

    fun shareAnalysis() {
        val data = controller?.analysisData().orEmpty()
        if (data.isBlank()) {
            showMessage("Connect to a vehicle and record some data before starting AI analysis.")
        } else {
            emit(UiEffect.Share(AiDiagnosticReport.build(data)))
        }
    }

    fun dismissMessage() {
        mutableUiState.update { it.copy(message = null) }
    }

    private fun applySessionState(session: ObdSessionState) {
        if (mutableUiState.value.demoRunning) return
        val capture = when (session.capturePhase) {
            CapturePhase.WAITING ->
                "Waiting for engine start · retains 60 s before and 10 s after" to UiTone.MUTED
            CapturePhase.ARMED ->
                "ARMED · retaining the previous 60 seconds" to UiTone.ACCENT
            CapturePhase.CAPTURING ->
                "CAPTURING · ${session.captureSecondsRemaining} s remaining" to UiTone.WARNING
            CapturePhase.READY ->
                "CAPTURE READY · share with ChatGPT / AI" to UiTone.ACCENT
        }
        val previous = mutableUiState.value
        mutableUiState.update {
            it.copy(
                status = session.status,
                statusTone = when (session.statusKind) {
                    SessionStatusKind.IDLE -> UiTone.MUTED
                    SessionStatusKind.WORKING -> UiTone.WARNING
                    SessionStatusKind.CONNECTED -> UiTone.ACCENT
                    SessionStatusKind.ERROR -> UiTone.ERROR
                },
                sample = session.sample,
                sessionActive = session.sessionActive,
                captureStatus = capture.first,
                captureTone = capture.second,
                message = if (
                    session.statusKind == SessionStatusKind.ERROR &&
                    previous.status != session.status
                ) {
                    "${session.status}\n\nOpen Diagnostics for the command log."
                } else {
                    it.message
                },
            )
        }
    }

    private fun updateSample(sample: ObdSample) {
        val capture = when {
            sample.event == StallEventRecorder.STALL_EVENT -> {
                captureUiUntil = sample.timestampMs + 10_000L
                captureReady = false
                "STALL_DETECTED · recording 10 seconds after event" to UiTone.ERROR
            }
            captureUiUntil > 0L && sample.timestampMs < captureUiUntil -> {
                val seconds = (captureUiUntil - sample.timestampMs + 999) / 1000
                "CAPTURING · $seconds s remaining" to UiTone.WARNING
            }
            captureUiUntil > 0L -> {
                captureUiUntil = 0L
                captureReady = true
                "CAPTURE READY · share with ChatGPT / AI" to UiTone.ACCENT
            }
            captureReady -> "CAPTURE READY · share with ChatGPT / AI" to UiTone.ACCENT
            sample.rpm >= 700.0f ->
                "ARMED · retaining the previous 60 seconds" to UiTone.ACCENT
            else ->
                "Waiting for engine start · retains 60 s before and 10 s after" to UiTone.MUTED
        }
        mutableUiState.update {
            it.copy(sample = sample, captureStatus = capture.first, captureTone = capture.second)
        }
    }

    private fun requestStopRecording(newStatus: String) {
        emit(UiEffect.StopRecording)
        clearCaptureState()
        mutableUiState.update {
            it.copy(
                status = newStatus,
                statusTone = UiTone.MUTED,
                sample = null,
                sessionActive = false,
            )
        }
    }

    private fun stopDemo(clearSample: Boolean) {
        demoJob?.cancel()
        demoJob = null
        mutableUiState.update { it.copy(demoRunning = false, sample = if (clearSample) null else it.sample) }
        clearCaptureState()
    }

    private fun clearCaptureState() {
        captureUiUntil = 0L
        captureReady = false
        mutableUiState.update {
            it.copy(
                captureStatus = "Waiting for engine start · retains 60 s before and 10 s after",
                captureTone = UiTone.MUTED,
            )
        }
    }

    private fun showMessage(message: String) {
        mutableUiState.update { it.copy(message = message) }
    }

    private fun emit(effect: UiEffect) {
        effectChannel.trySend(effect)
    }

    private fun demoSample(seconds: Float) = ObdSample(
        rpm = 900.0f + (sin((seconds * 2.0f).toDouble()) * 35.0).toFloat(),
        mapKpa = 42.0f,
        ecuVoltage = 14.18f,
        adapterVoltage = 14.22f,
        throttlePercent = 2.4f,
        relativeThrottlePercent = 1.9f,
        acceleratorPercent = 0.0f,
        shortTermFuelTrim = 1.6f,
        oxygen1Voltage = 0.72f,
        oxygen1Trim = 1.6f,
        oxygen2Voltage = 0.69f,
        oxygen2Trim = 0.8f,
        coolantC = 91.0f,
        intakeAirC = 28.0f,
        ignitionTiming = 8.5f,
        purgePercent = 0.0f,
        barometricKpa = 101.0f,
        fuelSystemStatus = "Closed loop",
        timestampMs = System.currentTimeMillis(),
    )

    private companion object {
        const val DEMO_ENGINE_START_SECONDS = 5.0f
        const val DEMO_STALL_SECONDS = 10.0f
    }

    class Factory : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            StallAnalyzerViewModel() as T
    }
}
