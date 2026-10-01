package com.melashkov.obdstallanalyzer.domain.capture

import com.melashkov.obdstallanalyzer.domain.model.ObdSample

internal data class CaptureState(
    val phase: Phase = Phase.WAITING,
    val secondsRemaining: Long = 0L,
) {
    enum class Phase {
        WAITING,
        ARMED,
        CAPTURING,
        READY,
    }
}

internal class UpdateCaptureStateUseCase(
    private val postEventWindowMs: Long = POST_EVENT_WINDOW_MS,
) {
    data class Result(
        val state: CaptureState,
        val stallDetected: Boolean,
        val captureCompleted: Boolean,
    )

    var state: CaptureState = CaptureState()
        private set

    private var previousRpm = Float.NaN
    private var engineArmed = false
    private var stallDetectedAt = 0L

    operator fun invoke(sample: ObdSample): Result {
        val stallDetected = engineArmed && state.phase != CaptureState.Phase.CAPTURING &&
            !previousRpm.isNaN() &&
            previousRpm >= STALL_PREVIOUS_RPM &&
            sample.rpm < STALL_RPM
        if (sample.rpm >= ARMING_RPM) engineArmed = true

        var captureCompleted = false
        state = when {
            stallDetected -> {
                stallDetectedAt = sample.timestampMs
                engineArmed = false
                CaptureState(
                    phase = CaptureState.Phase.CAPTURING,
                    secondsRemaining = remainingSeconds(sample.timestampMs),
                )
            }
            state.phase == CaptureState.Phase.CAPTURING &&
                sample.timestampMs - stallDetectedAt >= postEventWindowMs -> {
                captureCompleted = true
                CaptureState(CaptureState.Phase.READY)
            }
            state.phase == CaptureState.Phase.CAPTURING -> CaptureState(
                phase = CaptureState.Phase.CAPTURING,
                secondsRemaining = remainingSeconds(sample.timestampMs),
            )
            state.phase == CaptureState.Phase.READY -> state
            engineArmed -> CaptureState(CaptureState.Phase.ARMED)
            else -> CaptureState()
        }

        previousRpm = sample.rpm
        return Result(state, stallDetected, captureCompleted)
    }

    private fun remainingSeconds(nowMs: Long): Long =
        ((stallDetectedAt + postEventWindowMs - nowMs).coerceAtLeast(0L) + 999L) / 1_000L

    companion object {
        const val ARMING_RPM = 700.0f
        const val STALL_PREVIOUS_RPM = 600.0f
        const val STALL_RPM = 300.0f
        const val PRE_EVENT_WINDOW_MS = 60_000L
        const val POST_EVENT_WINDOW_MS = 10_000L
        const val PRE_EVENT_WINDOW_SECONDS = PRE_EVENT_WINDOW_MS / 1_000L
        const val POST_EVENT_WINDOW_SECONDS = POST_EVENT_WINDOW_MS / 1_000L
    }
}
