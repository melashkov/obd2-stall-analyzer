package com.melashkov.obdstallanalyzer.data.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.os.SystemClock
import android.util.Log
import com.melashkov.obdstallanalyzer.data.obd.ObdProtocol
import com.melashkov.obdstallanalyzer.domain.model.ObdSample
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.util.ArrayDeque
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive

internal class ElmBluetoothClient(
    private val adapter: BluetoothAdapter,
    private val device: BluetoothDevice,
) {
    private val diagnosticLog = ArrayDeque<String>()
    private val supportedPids = linkedSetOf<Int>()

    @Volatile
    private var socket: BluetoothSocket? = null
    @Volatile
    private var closed = false
    private var input: InputStream? = null
    private var output: OutputStream? = null

    private var rpm = Float.NaN
    private var mapKpa = Float.NaN
    private var ecuVoltage = Float.NaN
    private var adapterVoltage = Float.NaN
    private var throttlePercent = Float.NaN
    private var relativeThrottlePercent = Float.NaN
    private var acceleratorPercent = Float.NaN
    private var shortTermFuelTrim = Float.NaN
    private var oxygen1Voltage = Float.NaN
    private var oxygen1Trim = Float.NaN
    private var oxygen2Voltage = Float.NaN
    private var oxygen2Trim = Float.NaN
    private var coolantC = Float.NaN
    private var intakeAirC = Float.NaN
    private var ignitionTiming = Float.NaN
    private var purgePercent = Float.NaN
    private var barometricKpa = Float.NaN
    private var fuelSystemStatus = "Waiting for ECU"

    fun diagnosticLog(): String = diagnosticLog.joinToString(separator = "\n")

    @SuppressLint("MissingPermission")
    suspend fun run(
        onStatus: suspend (String) -> Unit,
        onConnected: suspend (String) -> Unit,
        onSample: suspend (ObdSample) -> Unit,
    ) {
        if (closed) throw CancellationException("Bluetooth session is closed")
        try {
            onStatus("Opening Bluetooth serial link…")
            adapter.cancelDiscovery()
            var candidate = device.createRfcommSocketToServiceRecord(SPP_UUID)
            socket = candidate
            try {
                candidate.connect()
            } catch (_: IOException) {
                currentCoroutineContext().ensureActive()
                if (closed) throw CancellationException("Bluetooth session is closed")
                closeSocket()
                log("Secure RFCOMM failed; trying insecure SPP")
                candidate = device.createInsecureRfcommSocketToServiceRecord(SPP_UUID)
                socket = candidate
                candidate.connect()
            }
            input = candidate.inputStream
            output = candidate.outputStream

            initializeAdapter(onStatus)
            onConnected(device.name ?: device.address)
            pollSensors(onStatus, onSample)
        } finally {
            closeSocket()
        }
    }

    private suspend fun initializeAdapter(onStatus: suspend (String) -> Unit) {
        onStatus("Resetting adapter…")
        try {
            transact("ATZ", 5_000)
        } catch (_: IOException) {
            currentCoroutineContext().ensureActive()
            log("ATZ did not return a prompt; continuing after reset")
            delay(800)
        }
        runObdDiscovery(onStatus)
    }

    private suspend fun runObdDiscovery(onStatus: suspend (String) -> Unit) {
        onStatus("Discovering the vehicle's OBD-II capabilities…")
        log("--- Standard OBD-II discovery ---")
        ObdProtocol.setupCommands.forEach { transact(it, 2_500) }

        ObdProtocol.discoveryCommands.forEach { command ->
            try {
                val response = transact(command, 5_000)
                if (command == "ATRV") {
                    adapterVoltage = ObdProtocol.parseAdapterVoltage(response)
                    log(String.format(Locale.US, "ADAPTER_VOLTAGE %.2f V", adapterVoltage))
                }
            } catch (error: Exception) {
                currentCoroutineContext().ensureActive()
                log("DISCOVERY $command unavailable: ${error.message}")
            }
        }

        ObdProtocol.capabilityCommands.forEach { command ->
            try {
                val response = transact(command, if (command == "0100") 20_000 else 5_000)
                val basePid = command.substring(2).toInt(16)
                supportedPids += ObdProtocol.parseSupportedPids(response, basePid)
            } catch (error: Exception) {
                currentCoroutineContext().ensureActive()
                log("CAPABILITY $command unavailable: ${error.message}")
            }
        }
        log(
            if (supportedPids.isEmpty()) {
                "PID_CAPABILITIES unavailable; optional values will be attempted"
            } else {
                "PID_CAPABILITIES " + supportedPids.joinToString(" ") {
                    String.format(Locale.US, "%02X", it)
                }
            },
        )
        log("--- Read-only generic OBD-II stall analyzer ---")
    }

    private suspend fun pollSensors(
        onStatus: suspend (String) -> Unit,
        onSample: suspend (ObdSample) -> Unit,
    ) {
        onStatus("Stall analyzer arms when the engine starts")
        val optionalCore = ObdProtocol.coreRequests.drop(1).filter(::supports)
        val auxiliary = ObdProtocol.auxiliaryRequests.filter(::supports)
        var cycle = 0
        var consecutiveFailures = 0

        while (currentCoroutineContext().isActive) {
            try {
                rpm = ObdProtocol.parseRpm(transact(ObdProtocol.READ_RPM, 2_200))
                consecutiveFailures = 0
            } catch (error: Exception) {
                currentCoroutineContext().ensureActive()
                consecutiveFailures++
                log("RPM read failure $consecutiveFailures: ${error.message}")
                if (consecutiveFailures >= 6) {
                    throw IOException("Live data stopped after repeated ECU timeouts", error)
                }
                delay(150)
                continue
            }

            optionalCore.forEach { command ->
                try {
                    updateValue(command, transact(command, 1_800))
                } catch (error: Exception) {
                    currentCoroutineContext().ensureActive()
                    log("Core PID $command skipped: ${error.message}")
                }
            }

            if (cycle % 8 == 0) {
                try {
                    adapterVoltage = ObdProtocol.parseAdapterVoltage(transact("ATRV", 1_800))
                } catch (error: Exception) {
                    currentCoroutineContext().ensureActive()
                    log("Adapter voltage skipped: ${error.message}")
                }
            }

            if (auxiliary.isNotEmpty()) {
                val command = auxiliary[cycle % auxiliary.size]
                try {
                    updateValue(command, transact(command, 1_800))
                } catch (error: Exception) {
                    currentCoroutineContext().ensureActive()
                    log("Auxiliary PID $command skipped: ${error.message}")
                }
            }

            val sample = currentSample(System.currentTimeMillis())
            onSample(sample)
            log(
                String.format(
                    Locale.US,
                    "DATA rpm=%.0f ecu=%sV adapter=%sV tps=%s map=%skPa stft=%s",
                    sample.rpm,
                    formatOptional(sample.ecuVoltage),
                    formatOptional(sample.adapterVoltage),
                    formatOptional(sample.throttlePercent),
                    formatOptional(sample.mapKpa),
                    formatOptional(sample.shortTermFuelTrim),
                ),
            )
            cycle++
            delay(25)
        }
    }

    private fun supports(command: String): Boolean {
        val pid = ObdProtocol.pidFor(command) ?: return true
        return supportedPids.isEmpty() || pid in supportedPids
    }

    private fun updateValue(command: String, response: String) {
        when (command) {
            ObdProtocol.READ_CONTROL_MODULE_VOLTAGE ->
                ecuVoltage = ObdProtocol.parseControlModuleVoltage(response)
            ObdProtocol.READ_THROTTLE ->
                throttlePercent = ObdProtocol.parseThrottlePercent(response, 0x11)
            ObdProtocol.READ_MAP -> mapKpa = ObdProtocol.parseMapKpa(response)
            ObdProtocol.READ_FUEL_SYSTEM ->
                fuelSystemStatus = ObdProtocol.parseFuelSystemStatus(response)
            ObdProtocol.READ_SHORT_TERM_FUEL_TRIM ->
                shortTermFuelTrim = ObdProtocol.parseShortTermFuelTrim(response)
            ObdProtocol.READ_OXYGEN_1 -> {
                oxygen1Voltage = ObdProtocol.parseOxygenVoltage(response, 0x14)
                oxygen1Trim = ObdProtocol.parseOxygenTrim(response, 0x14)
            }
            ObdProtocol.READ_OXYGEN_2 -> {
                oxygen2Voltage = ObdProtocol.parseOxygenVoltage(response, 0x15)
                oxygen2Trim = ObdProtocol.parseOxygenTrim(response, 0x15)
            }
            ObdProtocol.READ_COOLANT ->
                coolantC = ObdProtocol.parseTemperatureC(response, 0x05)
            ObdProtocol.READ_INTAKE_AIR ->
                intakeAirC = ObdProtocol.parseTemperatureC(response, 0x0F)
            ObdProtocol.READ_IGNITION_TIMING ->
                ignitionTiming = ObdProtocol.parseIgnitionTiming(response)
            ObdProtocol.READ_PURGE -> purgePercent = ObdProtocol.parsePurgePercent(response)
            ObdProtocol.READ_BAROMETRIC -> barometricKpa = ObdProtocol.parseBarometricKpa(response)
            ObdProtocol.READ_RELATIVE_THROTTLE ->
                relativeThrottlePercent = ObdProtocol.parseThrottlePercent(response, 0x45)
            ObdProtocol.READ_ACCELERATOR ->
                acceleratorPercent = ObdProtocol.parseThrottlePercent(response, 0x5A)
        }
    }

    private fun currentSample(timestampMs: Long) = ObdSample(
        rpm = rpm,
        mapKpa = mapKpa,
        ecuVoltage = ecuVoltage,
        adapterVoltage = adapterVoltage,
        throttlePercent = throttlePercent,
        relativeThrottlePercent = relativeThrottlePercent,
        acceleratorPercent = acceleratorPercent,
        shortTermFuelTrim = shortTermFuelTrim,
        oxygen1Voltage = oxygen1Voltage,
        oxygen1Trim = oxygen1Trim,
        oxygen2Voltage = oxygen2Voltage,
        oxygen2Trim = oxygen2Trim,
        coolantC = coolantC,
        intakeAirC = intakeAirC,
        ignitionTiming = ignitionTiming,
        purgePercent = purgePercent,
        barometricKpa = barometricKpa,
        fuelSystemStatus = fuelSystemStatus,
        timestampMs = timestampMs,
    )

    private suspend fun transact(command: String, timeoutMs: Long): String {
        val currentInput = input ?: throw IOException("Bluetooth link is not open")
        val currentOutput = output ?: throw IOException("Bluetooth link is not open")
        currentCoroutineContext().ensureActive()
        if (closed) throw CancellationException("Bluetooth session is closed")

        drainInput(currentInput)
        log("TX $command")
        currentOutput.write("$command\r".toByteArray(StandardCharsets.US_ASCII))
        currentOutput.flush()

        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        val response = ByteArrayOutputStream()
        while (SystemClock.elapsedRealtime() < deadline) {
            currentCoroutineContext().ensureActive()
            if (closed) throw CancellationException("Bluetooth session is closed")
            val available = currentInput.available()
            if (available <= 0) {
                delay(8)
                continue
            }
            repeat(available) {
                val value = currentInput.read()
                if (value < 0) throw IOException("Bluetooth stream closed")
                response.write(value)
                if (value == '>'.code) {
                    val text = response.toString(StandardCharsets.US_ASCII.name())
                    log("RX ${ObdProtocol.oneLine(text)}")
                    return text
                }
            }
        }
        throw IOException("Timed out waiting for adapter prompt after $command")
    }

    private fun drainInput(stream: InputStream) {
        while (stream.available() > 0) stream.read()
    }

    private fun log(line: String) {
        val entry = String.format(Locale.US, "%tT.%tL  %s", System.currentTimeMillis(), System.currentTimeMillis(), line)
        diagnosticLog.addLast(entry)
        Log.d(LOG_TAG, entry)
        while (diagnosticLog.size > MAX_LOG_LINES) diagnosticLog.removeFirst()
    }

    private fun closeSocket() {
        val current = socket
        socket = null
        try {
            current?.close()
        } catch (_: IOException) {
            // The link is already closing.
        }
    }

    fun close() {
        closed = true
        closeSocket()
    }

    private fun formatOptional(value: Float): String =
        if (value.isNaN()) "—" else String.format(Locale.US, "%.2f", value)

    companion object {
        private const val LOG_TAG = "ObdStallAnalyzer"
        private const val MAX_LOG_LINES = 400
        private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    }
}
