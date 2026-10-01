package com.melashkov.obdstallanalyzer

import java.util.ArrayDeque

internal class StallEventRecorder(
    private val preEventWindowMs: Long = 60_000L,
    private val postEventWindowMs: Long = 10_000L,
) {
    data class Update(
        val sample: ObdSample,
        val stallDetected: Boolean,
        val captureCompleted: Boolean,
    )

    enum class State {
        ROLLING,
        CAPTURING_AFTER_STALL,
        CAPTURE_READY,
    }

    private val rollingSamples = ArrayDeque<ObdSample>()
    private var activeCapture: MutableList<ObdSample>? = null
    private var latestCapture: List<ObdSample> = emptyList()
    private var previousRpm = Float.NaN
    private var engineArmed = false
    private var stallDetectedAt = 0L

    var state: State = State.ROLLING
        private set

    @Synchronized
    fun record(incoming: ObdSample): Update {
        trimRollingWindow(incoming.timestampMs)
        val stallDetected = engineArmed && activeCapture == null &&
            !previousRpm.isNaN() && previousRpm >= 600.0f && incoming.rpm < 300.0f
        if (incoming.rpm >= 700.0f) engineArmed = true

        val sample = if (stallDetected) incoming.copy(event = STALL_EVENT) else incoming
        var captureCompleted = false

        when {
            stallDetected -> {
                stallDetectedAt = sample.timestampMs
                activeCapture = rollingSamples.toMutableList().apply { add(sample) }
                rollingSamples.clear()
                engineArmed = false
                state = State.CAPTURING_AFTER_STALL
            }

            activeCapture != null -> {
                activeCapture!!.add(sample)
                if (sample.timestampMs - stallDetectedAt >= postEventWindowMs) {
                    latestCapture = activeCapture!!.toList()
                    activeCapture = null
                    captureCompleted = true
                    state = State.CAPTURE_READY
                }
            }

            else -> rollingSamples.addLast(sample)
        }

        previousRpm = sample.rpm
        return Update(sample, stallDetected, captureCompleted)
    }

    @Synchronized
    fun samplesForAnalysis(): List<ObdSample> = when {
        activeCapture != null -> activeCapture!!.toList()
        latestCapture.isNotEmpty() -> latestCapture
        else -> rollingSamples.toList()
    }

    private fun trimRollingWindow(nowMs: Long) {
        val earliest = nowMs - preEventWindowMs
        while (rollingSamples.isNotEmpty() && rollingSamples.first.timestampMs < earliest) {
            rollingSamples.removeFirst()
        }
    }

    companion object {
        const val STALL_EVENT = "STALL_DETECTED"
    }
}
