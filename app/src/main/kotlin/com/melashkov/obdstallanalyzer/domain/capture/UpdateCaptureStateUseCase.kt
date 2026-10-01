package com.melashkov.obdstallanalyzer.domain.capture

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
    private var stallDetectedAtElapsedRealtimeMs = 0L

    operator fun invoke(rpm: Float, elapsedRealtimeMs: Long): Result {
        val stallDetected = engineArmed && state.phase != CaptureState.Phase.CAPTURING &&
            !previousRpm.isNaN() &&
            previousRpm >= STALL_PREVIOUS_RPM &&
            rpm < STALL_RPM
        if (rpm >= ARMING_RPM) engineArmed = true

        var captureCompleted = false
        state = when {
            stallDetected -> {
                stallDetectedAtElapsedRealtimeMs = elapsedRealtimeMs
                engineArmed = false
                CaptureState(
                    phase = CaptureState.Phase.CAPTURING,
                    secondsRemaining = remainingSeconds(elapsedRealtimeMs),
                )
            }
            state.phase == CaptureState.Phase.CAPTURING &&
                elapsedRealtimeMs - stallDetectedAtElapsedRealtimeMs >= postEventWindowMs -> {
                captureCompleted = true
                CaptureState(CaptureState.Phase.READY)
            }
            state.phase == CaptureState.Phase.CAPTURING -> CaptureState(
                phase = CaptureState.Phase.CAPTURING,
                secondsRemaining = remainingSeconds(elapsedRealtimeMs),
            )
            state.phase == CaptureState.Phase.READY -> state
            engineArmed -> CaptureState(CaptureState.Phase.ARMED)
            else -> CaptureState()
        }

        previousRpm = rpm
        return Result(state, stallDetected, captureCompleted)
    }

    private fun remainingSeconds(elapsedRealtimeMs: Long): Long {
        val remainingMs =
            (stallDetectedAtElapsedRealtimeMs + postEventWindowMs - elapsedRealtimeMs)
                .coerceAtLeast(0L)
        return (remainingMs + 999L) / 1_000L
    }

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
