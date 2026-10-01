package com.melashkov.obdstallanalyzer.data.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import com.melashkov.obdstallanalyzer.data.obd.ObdCsv
import com.melashkov.obdstallanalyzer.domain.capture.StallEventRecorder
import com.melashkov.obdstallanalyzer.domain.model.ObdSample
import com.melashkov.obdstallanalyzer.domain.repository.ObdDevice
import com.melashkov.obdstallanalyzer.domain.repository.ObdEvent
import com.melashkov.obdstallanalyzer.domain.repository.ObdRepository
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import java.util.Locale

internal class BluetoothObdRepository(
    private val adapter: BluetoothAdapter?,
) : ObdRepository {
    private val lock = Any()
    private var client: ElmBluetoothClient? = null
    private var recorder = StallEventRecorder()
    private var lastDiagnosticLog = "No connection has been attempted."
    private var lastAnalysisData = ""

    override val isAvailable: Boolean get() = adapter != null

    @get:SuppressLint("MissingPermission")
    override val isEnabled: Boolean get() = adapter?.isEnabled == true

    @SuppressLint("MissingPermission")
    override fun pairedDevices(): List<ObdDevice> = adapter?.bondedDevices.orEmpty()
        .map { ObdDevice(it.address, it.name ?: "Bluetooth device") }
        .sortedBy { it.name.lowercase(Locale.US) }

    @SuppressLint("MissingPermission")
    override fun observe(deviceId: String): Flow<ObdEvent> = callbackFlow {
        val bluetoothAdapter = adapter
        if (bluetoothAdapter == null) {
            trySend(ObdEvent.Failed("Bluetooth is not available on this device."))
            close()
            return@callbackFlow
        }
        val device = bluetoothAdapter.bondedDevices.firstOrNull { it.address == deviceId }
        if (device == null) {
            trySend(ObdEvent.Failed("The selected adapter is no longer paired."))
            close()
            return@callbackFlow
        }

        disconnect()
        synchronized(lock) {
            recorder = StallEventRecorder()
            lastDiagnosticLog = ""
            lastAnalysisData = ""
        }
        lateinit var sessionClient: ElmBluetoothClient
        sessionClient = ElmBluetoothClient(
            adapter = bluetoothAdapter,
            device = device,
            listener = object : ElmBluetoothClient.Listener {
                override fun onStatus(message: String) {
                    trySend(ObdEvent.Status(message))
                }

                override fun onConnected(deviceName: String) {
                    trySend(ObdEvent.Connected(deviceName))
                }

                override fun onSample(sample: ObdSample) {
                    val recorded = synchronized(lock) { recorder.record(sample).sample }
                    trySend(ObdEvent.SampleReceived(recorded))
                }

                override fun onError(message: String, error: Throwable) {
                    snapshot(sessionClient)
                    trySend(ObdEvent.Failed(message))
                }

                override fun onDisconnected() {
                    snapshot(sessionClient)
                    trySend(ObdEvent.Disconnected)
                    close()
                }
            },
        )
        synchronized(lock) { client = sessionClient }
        sessionClient.connect()

        awaitClose {
            synchronized(lock) {
                if (client === sessionClient) {
                    snapshotLocked()
                    client = null
                }
            }
            sessionClient.disconnect()
        }
    }

    override fun diagnosticLog(): String = synchronized(lock) {
        buildDiagnosticLog(client?.getDiagnosticLog() ?: lastDiagnosticLog)
    }

    override fun analysisData(): String = synchronized(lock) {
        val samples = recorder.samplesForAnalysis()
        if (samples.isEmpty()) lastAnalysisData else ObdCsv.build(samples, recorder.state)
    }

    override fun disconnect() {
        val current = synchronized(lock) {
            snapshotLocked()
            client.also { client = null }
        }
        current?.disconnect()
    }

    private fun snapshot(sessionClient: ElmBluetoothClient) = synchronized(lock) {
        if (client === sessionClient) snapshotLocked()
    }

    private fun snapshotLocked() {
        client?.getDiagnosticLog()?.let { lastDiagnosticLog = it }
        val samples = recorder.samplesForAnalysis()
        if (samples.isNotEmpty()) lastAnalysisData = ObdCsv.build(samples, recorder.state)
    }

    private fun buildDiagnosticLog(transportLog: String): String = buildString {
        append(transportLog)
        val samples = recorder.samplesForAnalysis()
        if (samples.isNotEmpty()) {
            append("\n--- Retained sensor data ---\n")
            append(ObdCsv.build(samples, recorder.state))
        }
    }
}
