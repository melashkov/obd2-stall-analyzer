package com.melashkov.obdstallanalyzer

import java.util.Locale

/** Standard, read-only OBD-II commands and decoders. */
internal object ObdProtocol {
    const val READ_RPM = "010C"
    const val READ_MAP = "010B"
    const val READ_THROTTLE = "0111"
    const val READ_CONTROL_MODULE_VOLTAGE = "0142"
    const val READ_FUEL_SYSTEM = "0103"
    const val READ_SHORT_TERM_FUEL_TRIM = "0106"
    const val READ_OXYGEN_1 = "0114"
    const val READ_OXYGEN_2 = "0115"
    const val READ_COOLANT = "0105"
    const val READ_INTAKE_AIR = "010F"
    const val READ_IGNITION_TIMING = "010E"
    const val READ_PURGE = "012E"
    const val READ_BAROMETRIC = "0133"
    const val READ_RELATIVE_THROTTLE = "0145"
    const val READ_ACCELERATOR = "015A"

    val setupCommands = listOf("ATE0", "ATL0", "ATS0", "ATH1", "ATCAF1", "ATSP0")
    val capabilityCommands = listOf("0100", "0120", "0140", "0160")
    val discoveryCommands = listOf("ATI", "ATRV", "ATDP", "ATDPN", "03", "07", "0A")

    val coreRequests = listOf(
        READ_RPM,
        READ_CONTROL_MODULE_VOLTAGE,
        READ_THROTTLE,
        READ_MAP,
    )

    val auxiliaryRequests = listOf(
        READ_FUEL_SYSTEM,
        READ_SHORT_TERM_FUEL_TRIM,
        READ_OXYGEN_1,
        READ_OXYGEN_2,
        READ_COOLANT,
        READ_INTAKE_AIR,
        READ_IGNITION_TIMING,
        READ_PURGE,
        READ_BAROMETRIC,
        READ_RELATIVE_THROTTLE,
        READ_ACCELERATOR,
    )

    fun pidFor(command: String): Int? =
        command.takeIf { it.length == 4 && it.startsWith("01") }?.substring(2)?.toIntOrNull(16)

    fun parseSupportedPids(response: String, basePid: Int): Set<Int> {
        val marker = String.format(Locale.US, "41%02X", basePid)
        val result = linkedSetOf<Int>()
        response.uppercase(Locale.US).split(Regex("[\\r\\n>]+"))
            .map { it.replace(Regex("[^0-9A-F]"), "") }
            .forEach { compact ->
                var markerIndex = compact.indexOf(marker)
                while (markerIndex >= 0) {
                    val valueIndex = markerIndex + marker.length
                    if (compact.length >= valueIndex + 8) {
                        val bitmap = compact.substring(valueIndex, valueIndex + 8).toLongOrNull(16)
                        if (bitmap != null) {
                            for (bit in 0 until 32) {
                                if ((bitmap and (1L shl (31 - bit))) != 0L) {
                                    result += basePid + bit + 1
                                }
                            }
                        }
                    }
                    markerIndex = compact.indexOf(marker, markerIndex + marker.length)
                }
            }
        return result
    }

    fun parseControlModuleVoltage(response: String): Float {
        val bytes = parseMode01(response, 0x42, 2)
        val volts = ((bytes[0] shl 8) or bytes[1]) / 1000.0f
        if (volts !in 5.0f..40.0f) {
            throw ProtocolException("Control-module voltage outside expected range: $volts")
        }
        return volts
    }

    fun parseRpm(response: String): Float {
        val bytes = parseMode01(response, 0x0C, 2)
        return ((bytes[0] shl 8) or bytes[1]) / 4.0f
    }

    fun parseMapKpa(response: String): Float = parseMode01(response, 0x0B, 1)[0].toFloat()

    fun parseThrottlePercent(response: String, pid: Int): Float =
        parseMode01(response, pid, 1)[0] * 100.0f / 255.0f

    fun parseShortTermFuelTrim(response: String): Float =
        (parseMode01(response, 0x06, 1)[0] - 128) * 100.0f / 128.0f

    fun parseOxygenVoltage(response: String, pid: Int): Float =
        parseMode01(response, pid, 2)[0] / 200.0f

    fun parseOxygenTrim(response: String, pid: Int): Float {
        val trim = parseMode01(response, pid, 2)[1]
        return if (trim == 0xFF) Float.NaN else (trim - 128) * 100.0f / 128.0f
    }

    fun parseTemperatureC(response: String, pid: Int): Float =
        parseMode01(response, pid, 1)[0] - 40.0f

    fun parseIgnitionTiming(response: String): Float =
        parseMode01(response, 0x0E, 1)[0] / 2.0f - 64.0f

    fun parsePurgePercent(response: String): Float =
        parseMode01(response, 0x2E, 1)[0] * 100.0f / 255.0f

    fun parseBarometricKpa(response: String): Float =
        parseMode01(response, 0x33, 1)[0].toFloat()

    fun parseFuelSystemStatus(response: String): String =
        when (val status = parseMode01(response, 0x03, 2)[0]) {
            1 -> "Open loop · warming"
            2 -> "Closed loop"
            4 -> "Open loop · load/decel"
            8 -> "Open loop · system fault"
            16 -> "Closed loop · O₂ fault"
            0 -> "Unavailable"
            else -> String.format(Locale.US, "Status 0x%02X", status)
        }

    fun parseAdapterVoltage(response: String): Float {
        val numeric = response.uppercase(Locale.US)
            .replace(Regex("[^0-9.]"), " ")
            .trim()
            .split(Regex("\\s+"))
            .firstOrNull()
        val volts = numeric?.toFloatOrNull()
        if (volts != null && volts in 5.0f..40.0f) return volts
        throw ProtocolException("No valid adapter voltage response: ${oneLine(response)}")
    }

    private fun parseMode01(response: String, pid: Int, byteCount: Int): IntArray {
        val marker = String.format(Locale.US, "41%02X", pid)
        response.uppercase(Locale.US).split(Regex("[\\r\\n>]+"))
            .map { it.replace(Regex("[^0-9A-F]"), "") }
            .forEach { compact ->
                val markerIndex = compact.indexOf(marker)
                val valueIndex = markerIndex + marker.length
                if (markerIndex < 0 || compact.length < valueIndex + byteCount * 2) return@forEach
                val result = IntArray(byteCount)
                try {
                    for (index in 0 until byteCount) {
                        val offset = valueIndex + index * 2
                        result[index] = compact.substring(offset, offset + 2).toInt(16)
                    }
                    return result
                } catch (_: NumberFormatException) {
                    // Try another returned ECU line.
                }
            }
        throw ProtocolException(
            "No positive Mode 01 response for PID " +
                String.format(Locale.US, "%02X: %s", pid, oneLine(response)),
        )
    }

    fun oneLine(value: String?): String {
        if (value == null) return ""
        val result = value.replace('\r', ' ').replace('\n', ' ').trim()
        return if (result.length > 160) result.take(160) + "…" else result
    }

    class ProtocolException(message: String) : Exception(message)
}
