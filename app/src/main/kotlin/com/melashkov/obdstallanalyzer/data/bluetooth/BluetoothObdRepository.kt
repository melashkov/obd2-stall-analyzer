package com.melashkov.obdstallanalyzer.data.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import com.melashkov.obdstallanalyzer.data.obd.ObdCsv
import com.melashkov.obdstallanalyzer.domain.capture.StallEventRecorder
import com.melashkov.obdstallanalyzer.domain.model.ObdSample
import com.melashkov.obdstallanalyzer.domain.repository.ObdDevice
import com.melashkov.obdstallanalyzer.domain.repository.ObdEvent
import com.melashkov.obdstallanalyzer.domain.repository.ObdRepository
import java.util.Locale
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch

internal class BluetoothObdRepository(
    private val adapter: BluetoothAdapter?,
) : ObdRepository {
    private data class SessionSnapshot(
        val diagnosticLog: String,
        val samples: List<ObdSample>,
        val recorderState: StallEventRecorder.State,
    )

    private val activeClient = AtomicReference<ElmBluetoothClient?>()
    private val latestSnapshot = AtomicReference(
        SessionSnapshot(
            diagnosticLog = "No connection has been attempted.",
            samples = emptyList(),
            recorderState = StallEventRecorder.State.ROLLING,
        ),
    )

    override val isAvailable: Boolean get() = adapter != null

    @get:SuppressLint("MissingPermission")
    override val isEnabled: Boolean get() = adapter?.isEnabled == true

    @SuppressLint("MissingPermission")
    override fun pairedDevices(): List<ObdDevice> = adapter?.bondedDevices.orEmpty()
        .map { ObdDevice(it.address, it.name ?: "Bluetooth device") }
        .sortedBy { it.name.lowercase(Locale.US) }

    @SuppressLint("MissingPermission")
    override fun observe(deviceId: String): Flow<ObdEvent> = channelFlow {
        val bluetoothAdapter = adapter
        if (bluetoothAdapter == null) {
            send(ObdEvent.Failed("Bluetooth is not available on this device."))
            return@channelFlow
        }
        val device = bluetoothAdapter.bondedDevices.firstOrNull { it.address == deviceId }
        if (device == null) {
            send(ObdEvent.Failed("The selected adapter is no longer paired."))
            return@channelFlow
        }

        val recorder = StallEventRecorder()
        val client = ElmBluetoothClient(bluetoothAdapter, device)
        activeClient.getAndSet(client)?.close()
        latestSnapshot.set(
            SessionSnapshot("", emptyList(), StallEventRecorder.State.ROLLING),
        )

        fun publishSnapshot() {
            if (activeClient.get() !== client) return
            latestSnapshot.set(
                SessionSnapshot(
                    diagnosticLog = client.diagnosticLog(),
                    samples = recorder.samplesForAnalysis(),
                    recorderState = recorder.state,
                ),
            )
        }

        val connectionJob = launch(Dispatchers.IO) {
            try {
                client.run(
                    onStatus = { message ->
                        publishSnapshot()
                        send(ObdEvent.Status(message))
                    },
                    onConnected = { deviceName ->
                        publishSnapshot()
                        send(ObdEvent.Connected(deviceName))
                    },
                    onSample = { sample ->
                        val recorded = recorder.record(sample).sample
                        publishSnapshot()
                        send(ObdEvent.SampleReceived(recorded))
                    },
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                publishSnapshot()
                send(ObdEvent.Failed(error.message ?: "Connection failed"))
            } finally {
                client.close()
                publishSnapshot()
                activeClient.compareAndSet(client, null)
                close()
            }
        }

        awaitClose {
            client.close()
            connectionJob.cancel()
        }
    }

    override fun diagnosticLog(): String {
        val snapshot = latestSnapshot.get()
        return buildString {
            append(snapshot.diagnosticLog)
            if (snapshot.samples.isNotEmpty()) {
                append("\n--- Retained sensor data ---\n")
                append(ObdCsv.build(snapshot.samples, snapshot.recorderState))
            }
        }
    }

    override fun analysisData(): String {
        val snapshot = latestSnapshot.get()
        return if (snapshot.samples.isEmpty()) {
            ""
        } else {
            ObdCsv.build(snapshot.samples, snapshot.recorderState)
        }
    }

    override fun disconnect() {
        activeClient.get()?.close()
    }
}
