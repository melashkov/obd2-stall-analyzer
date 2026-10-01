package com.melashkov.obdstallanalyzer

import org.junit.Assert.assertTrue
import org.junit.Test

class AiDiagnosticReportTest {
    @Test
    fun createsGenericEvidenceFocusedPrompt() {
        val report = AiDiagnosticReport.build("time,rpm,event\n123,0,STALL_DETECTED\n")

        assertTrue(report.contains("Vehicle: add make, model, year"))
        assertTrue(report.contains("60 seconds before STALL_DETECTED and 10 seconds after"))
        assertTrue(report.contains("Separate observations from hypotheses"))
        assertTrue(report.contains("Do not invent values"))
        assertTrue(report.trimEnd().endsWith("time,rpm,event\n123,0,STALL_DETECTED"))
    }
}
