package com.melashkov.obdstallanalyzer

import com.melashkov.obdstallanalyzer.domain.model.ObdSample
import com.melashkov.obdstallanalyzer.domain.repository.ObdDevice
import com.melashkov.obdstallanalyzer.domain.session.CapturePhase
import com.melashkov.obdstallanalyzer.domain.session.ObdSessionController
import com.melashkov.obdstallanalyzer.domain.session.ObdSessionState
import com.melashkov.obdstallanalyzer.domain.session.SessionStatusKind
import com.melashkov.obdstallanalyzer.presentation.StallAnalyzerViewModel
import com.melashkov.obdstallanalyzer.presentation.UiEffect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class StallAnalyzerViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun connectFlowUpdatesScreenState() = runTest(dispatcher) {
        val controller = FakeSessionController()
        val viewModel = StallAnalyzerViewModel()
        viewModel.attachController(controller)
        advanceUntilIdle()

        viewModel.onConnectClicked(hasBluetoothPermission = true)
        assertEquals(controller.devices, viewModel.uiState.value.pairedDevices)

        viewModel.connect(controller.devices.single())
        controller.mutableState.value = ObdSessionState(
            status = "Recording · Test adapter",
            statusKind = SessionStatusKind.CONNECTED,
            sample = sample(rpm = 820f),
            sessionActive = true,
            capturePhase = CapturePhase.ARMED,
        )
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue(state.sessionActive)
        assertEquals("Recording · Test adapter", state.status)
        assertEquals(820f, state.sample?.rpm)
        assertEquals("ARMED · retaining the previous 60 seconds", state.captureStatus)
        assertNull(state.pairedDevices)
    }

    @Test
    fun disconnectRequestsServiceStopAndClearsReading() = runTest(dispatcher) {
        val controller = FakeSessionController()
        val viewModel = StallAnalyzerViewModel()
        viewModel.attachController(controller)
        controller.mutableState.value = ObdSessionState(
            status = "Recording · Test adapter",
            statusKind = SessionStatusKind.CONNECTED,
            sample = sample(rpm = 900f),
            sessionActive = true,
            capturePhase = CapturePhase.ARMED,
        )
        advanceUntilIdle()

        val effect = async { viewModel.effects.first() }
        viewModel.onConnectClicked(hasBluetoothPermission = true)

        assertFalse(viewModel.uiState.value.sessionActive)
        assertNull(viewModel.uiState.value.sample)
        assertEquals("Disconnected", viewModel.uiState.value.status)
        assertEquals(UiEffect.StopRecording, effect.await())
    }

    private fun sample(rpm: Float) = ObdSample(rpm = rpm, timestampMs = 1_000L)

    private class FakeSessionController : ObdSessionController {
        val devices = listOf(ObdDevice("device-id", "Test adapter"))
        val mutableState = MutableStateFlow(ObdSessionState())
        override val state: StateFlow<ObdSessionState> = mutableState

        override val isAvailable = true
        override val isEnabled = true

        override fun pairedDevices(): List<ObdDevice> = devices
        override fun diagnosticLog(): String = "diagnostics"
        override fun analysisData(): String = ""
    }
}
