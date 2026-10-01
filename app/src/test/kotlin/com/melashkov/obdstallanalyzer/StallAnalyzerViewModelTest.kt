package com.melashkov.obdstallanalyzer

import com.melashkov.obdstallanalyzer.domain.model.ObdSample
import com.melashkov.obdstallanalyzer.domain.repository.ObdDevice
import com.melashkov.obdstallanalyzer.domain.repository.ObdEvent
import com.melashkov.obdstallanalyzer.domain.repository.ObdRepository
import com.melashkov.obdstallanalyzer.presentation.StallAnalyzerViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
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
        val repository = FakeObdRepository()
        val viewModel = StallAnalyzerViewModel(repository)

        viewModel.onConnectClicked(hasBluetoothPermission = true)
        assertEquals(repository.devices, viewModel.uiState.value.pairedDevices)

        viewModel.connect(repository.devices.single())
        advanceUntilIdle()
        repository.events.emit(ObdEvent.Connected("Test adapter"))
        repository.events.emit(ObdEvent.SampleReceived(sample(rpm = 820f)))
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue(state.sessionActive)
        assertEquals("Recording · Test adapter", state.status)
        assertEquals(820f, state.sample?.rpm)
        assertEquals("ARMED · retaining the previous 60 seconds", state.captureStatus)
        assertNull(state.pairedDevices)
    }

    @Test
    fun disconnectCancelsRepositorySessionAndClearsReading() = runTest(dispatcher) {
        val repository = FakeObdRepository()
        val viewModel = StallAnalyzerViewModel(repository)
        viewModel.connect(repository.devices.single())
        advanceUntilIdle()
        repository.events.emit(ObdEvent.SampleReceived(sample(rpm = 900f)))
        advanceUntilIdle()

        viewModel.onConnectClicked(hasBluetoothPermission = true)

        assertFalse(viewModel.uiState.value.sessionActive)
        assertNull(viewModel.uiState.value.sample)
        assertEquals("Disconnected", viewModel.uiState.value.status)
        assertTrue(repository.disconnectCalled)
    }

    private fun sample(rpm: Float) = ObdSample(rpm = rpm, timestampMs = 1_000L)

    private class FakeObdRepository : ObdRepository {
        val devices = listOf(ObdDevice("device-id", "Test adapter"))
        val events = MutableSharedFlow<ObdEvent>(extraBufferCapacity = 8)
        var disconnectCalled = false

        override val isAvailable = true
        override val isEnabled = true

        override fun pairedDevices(): List<ObdDevice> = devices
        override fun observe(deviceId: String): Flow<ObdEvent> = events
        override fun diagnosticLog(): String = "diagnostics"
        override fun analysisData(): String = ""
        override fun disconnect() {
            disconnectCalled = true
        }
    }
}
