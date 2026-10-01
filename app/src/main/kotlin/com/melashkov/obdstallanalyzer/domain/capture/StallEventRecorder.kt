package com.melashkov.obdstallanalyzer.domain.capture

import com.melashkov.obdstallanalyzer.domain.model.ObdSample
import java.util.ArrayDeque

internal class StallEventRecorder(
    private val clock: MonotonicClock,
    private val preEventWindowMs: Long = UpdateCaptureStateUseCase.PRE_EVENT_WINDOW_MS,
    postEventWindowMs: Long = UpdateCaptureStateUseCase.POST_EVENT_WINDOW_MS,
) {
    private data class TimedSample(
        val sample: ObdSample,
        val elapsedRealtimeMs: Long,
    )

    data class Update(
        val sample: ObdSample,
        val stallDetected: Boolean,
        val captureCompleted: Boolean,
        val captureState: CaptureState,
    )

    private val rollingSamples = ArrayDeque<TimedSample>()
    private var activeCapture: MutableList<ObdSample>? = null
    private var latestCapture: List<ObdSample> = emptyList()
    private val updateCaptureState = UpdateCaptureStateUseCase(postEventWindowMs)

    val captureState: CaptureState get() = updateCaptureState.state

    fun record(incoming: ObdSample): Update {
        val elapsedRealtimeMs = clock.elapsedRealtimeMs()
        trimRollingWindow(elapsedRealtimeMs)
        val captureUpdate = updateCaptureState(incoming.rpm, elapsedRealtimeMs)
        val sample = if (captureUpdate.stallDetected) {
            incoming.copy(event = STALL_EVENT)
        } else {
            incoming
        }

        when {
            captureUpdate.stallDetected -> {
                activeCapture = rollingSamples.mapTo(mutableListOf()) { it.sample }.apply {
                    add(sample)
                }
                rollingSamples.clear()
            }

            activeCapture != null -> {
                activeCapture!!.add(sample)
                if (captureUpdate.captureCompleted) {
                    latestCapture = activeCapture!!.toList()
                    activeCapture = null
                }
            }

            else -> rollingSamples.addLast(TimedSample(sample, elapsedRealtimeMs))
        }

        return Update(
            sample = sample,
            stallDetected = captureUpdate.stallDetected,
            captureCompleted = captureUpdate.captureCompleted,
            captureState = captureUpdate.state,
        )
    }

    fun samplesForAnalysis(): List<ObdSample> = when {
        activeCapture != null -> activeCapture!!.toList()
        latestCapture.isNotEmpty() -> latestCapture
        else -> rollingSamples.map { it.sample }
    }

    private fun trimRollingWindow(elapsedRealtimeMs: Long) {
        val earliest = elapsedRealtimeMs - preEventWindowMs
        while (
            rollingSamples.isNotEmpty() &&
            rollingSamples.first.elapsedRealtimeMs < earliest
        ) {
            rollingSamples.removeFirst()
        }
    }

    companion object {
        const val STALL_EVENT = "STALL_DETECTED"
    }
}
