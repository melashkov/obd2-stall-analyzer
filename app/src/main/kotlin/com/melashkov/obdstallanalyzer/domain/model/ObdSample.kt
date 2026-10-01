package com.melashkov.obdstallanalyzer.domain.model

data class ObdSample(
    val rpm: Float,
    val mapKpa: Float = Float.NaN,
    val ecuVoltage: Float = Float.NaN,
    val adapterVoltage: Float = Float.NaN,
    val throttlePercent: Float = Float.NaN,
    val relativeThrottlePercent: Float = Float.NaN,
    val acceleratorPercent: Float = Float.NaN,
    val shortTermFuelTrim: Float = Float.NaN,
    val oxygen1Voltage: Float = Float.NaN,
    val oxygen1Trim: Float = Float.NaN,
    val oxygen2Voltage: Float = Float.NaN,
    val oxygen2Trim: Float = Float.NaN,
    val coolantC: Float = Float.NaN,
    val intakeAirC: Float = Float.NaN,
    val ignitionTiming: Float = Float.NaN,
    val purgePercent: Float = Float.NaN,
    val barometricKpa: Float = Float.NaN,
    val fuelSystemStatus: String = "Waiting for ECU",
    val event: String = "",
    val timestampMs: Long,
)
