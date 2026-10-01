package com.melashkov.obdstallanalyzer

import com.melashkov.obdstallanalyzer.data.obd.ObdProtocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ObdProtocolTest {
    @Test
    fun usesGenericAutomaticObdDiscovery() {
        assertEquals(
            listOf("ATE0", "ATL0", "ATS0", "ATH1", "ATCAF1", "ATSP0"),
            ObdProtocol.setupCommands,
        )
        assertEquals(listOf("0100", "0120", "0140", "0160"), ObdProtocol.capabilityCommands)
    }

    @Test
    fun parsesAdvertisedPidBitmap() {
        val supported = ObdProtocol.parseSupportedPids("18DAF11006410000308000\r>", 0x00)
        assertEquals(setOf(0x0B, 0x0C, 0x11), supported)
    }

    @Test
    fun decodesStandardCoreSignals() {
        assertEquals(12.601f,
            ObdProtocol.parseControlModuleVoltage("18DAF1D60441423139\r>"), 0.001f)
        assertEquals(1125.0f, ObdProtocol.parseRpm("18DAF1D604410C1194\r>"), 0.01f)
        assertEquals(102.0f, ObdProtocol.parseMapKpa("18DAF1D603410B66\r>"), 0.01f)
        assertEquals(50.196f,
            ObdProtocol.parseThrottlePercent("18DAF1D603411180\r>", 0x11), 0.001f)
    }

    @Test
    fun decodesStandardMixtureAndEnvironmentalSignals() {
        assertEquals("Closed loop", ObdProtocol.parseFuelSystemStatus("41030200\r>"))
        assertEquals(0.0f, ObdProtocol.parseShortTermFuelTrim("410680\r>"), 0.001f)
        assertEquals(0.765f, ObdProtocol.parseOxygenVoltage("41149980\r>", 0x14), 0.001f)
        assertEquals(91.0f, ObdProtocol.parseTemperatureC("410583\r>", 0x05), 0.01f)
        assertEquals(8.5f, ObdProtocol.parseIgnitionTiming("410E91\r>"), 0.01f)
        assertEquals(101.0f, ObdProtocol.parseBarometricKpa("413365\r>"), 0.01f)
        assertEquals(12.1f, ObdProtocol.parseAdapterVoltage("12.1V\r>"), 0.01f)
        assertTrue(ObdProtocol.pidFor("010C") == 0x0C)
    }
}
