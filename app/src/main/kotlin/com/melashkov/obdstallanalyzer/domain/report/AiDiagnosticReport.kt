package com.melashkov.obdstallanalyzer.domain.report

import com.melashkov.obdstallanalyzer.domain.capture.UpdateCaptureStateUseCase

internal object AiDiagnosticReport {
    fun build(recorderOutput: String): String {
        val stallRule =
            "Stall rule: recorder armed at ${UpdateCaptureStateUseCase.ARMING_RPM.toInt()} rpm; " +
                "the previous sample was at least " +
                "${UpdateCaptureStateUseCase.STALL_PREVIOUS_RPM.toInt()} rpm and the next was " +
                "below ${UpdateCaptureStateUseCase.STALL_RPM.toInt()} rpm"
        val captureWindow =
            "Capture window: up to ${UpdateCaptureStateUseCase.PRE_EVENT_WINDOW_SECONDS} " +
                "seconds before STALL_DETECTED and " +
                "${UpdateCaptureStateUseCase.POST_EVENT_WINDOW_SECONDS} seconds after it"
        return """
        OBD STALL EVENT — AI DIAGNOSTIC REQUEST

        Vehicle: add make, model, year, engine and fuel type
        Interface: Bluetooth ELM327-compatible OBD-II adapter
        Collection method: read-only standard OBD-II Mode 01 requests
        $stallRule
        $captureWindow

        Please analyze the data as follows:
        1. Reconstruct the sequence immediately before and after STALL_DETECTED.
        2. Check ECU/adapter voltage, RPM, TPS, MAP, fuel trim, O2 behavior, temperatures,
           ignition timing, purge and fuel-system state where those signals are present.
        3. Rank plausible causes by confidence and cite exact timestamps and values.
           Separate observations from hypotheses.
        4. Explain whether the pattern resembles intentional key-off, electrical interruption,
           air/fueling trouble, a sensor problem, or insufficient data.
        5. Recommend safe, non-invasive checks. Do not recommend programming, adaptations,
           fault clearing or replacing parts without supporting evidence.
        6. Treat blank or repeated cells as potentially missing/stale values. Do not invent values.

        Add these details before analysis if known: vehicle identity, warning lights, diagnostic
        trouble codes, hot/cold engine, fuel level, weather, speed/gear, recent work, and whether
        the engine restarted immediately.

        --- RECORDER OUTPUT ---
        $recorderOutput
    """.trimIndent()
    }
}
