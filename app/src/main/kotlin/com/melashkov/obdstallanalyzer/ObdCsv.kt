package com.melashkov.obdstallanalyzer

import java.util.Locale

internal object ObdCsv {
    fun build(samples: List<ObdSample>, state: StallEventRecorder.State): String {
        val csv = StringBuilder()
        csv.append("capture_state,").append(state.name).append('\n')
        csv.append("time_ms,time,rpm,ecu_v,adapter_v,tps_pct,relative_tps_pct,accelerator_pct,")
            .append("map_kpa,stft_pct,o2_1_v,o2_1_trim_pct,o2_2_v,o2_2_trim_pct,")
            .append("coolant_c,intake_c,timing_deg,purge_pct,baro_kpa,fuel_status,event\n")
        samples.forEach { sample ->
            csv.append(sample.timestampMs).append(',')
                .append(String.format(Locale.US, "%tT.%tL", sample.timestampMs, sample.timestampMs)).append(',')
                .append(format(sample.rpm)).append(',')
                .append(format(sample.ecuVoltage)).append(',')
                .append(format(sample.adapterVoltage)).append(',')
                .append(format(sample.throttlePercent)).append(',')
                .append(format(sample.relativeThrottlePercent)).append(',')
                .append(format(sample.acceleratorPercent)).append(',')
                .append(format(sample.mapKpa)).append(',')
                .append(format(sample.shortTermFuelTrim)).append(',')
                .append(format(sample.oxygen1Voltage)).append(',')
                .append(format(sample.oxygen1Trim)).append(',')
                .append(format(sample.oxygen2Voltage)).append(',')
                .append(format(sample.oxygen2Trim)).append(',')
                .append(format(sample.coolantC)).append(',')
                .append(format(sample.intakeAirC)).append(',')
                .append(format(sample.ignitionTiming)).append(',')
                .append(format(sample.purgePercent)).append(',')
                .append(format(sample.barometricKpa)).append(',')
                .append(sample.fuelSystemStatus.replace(',', ';')).append(',')
                .append(sample.event)
                .append('\n')
        }
        return csv.toString()
    }

    private fun format(value: Float): String =
        if (value.isNaN()) "" else String.format(Locale.US, "%.3f", value)
}
