package com.melashkov.obdstallanalyzer.domain.report

internal object AiDiagnosticReport {
    fun build(recorderOutput: String): String = """
        OBD STALL EVENT — AI DIAGNOSTIC REQUEST

        Vehicle: add make, model, year, engine and fuel type
        Interface: Bluetooth ELM327-compatible OBD-II adapter
        Collection method: read-only standard OBD-II Mode 01 requests
        Stall rule: RPM was previously above 700 rpm, then fell below 300 rpm
        Capture window: up to 60 seconds before STALL_DETECTED and 10 seconds after it

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
