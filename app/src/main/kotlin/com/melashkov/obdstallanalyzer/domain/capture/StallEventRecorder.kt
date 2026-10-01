package com.melashkov.obdstallanalyzer.domain.capture

import com.melashkov.obdstallanalyzer.domain.model.ObdSample
import java.util.ArrayDeque

internal class StallEventRecorder(
    private val preEventWindowMs: Long = UpdateCaptureStateUseCase.PRE_EVENT_WINDOW_MS,
    postEventWindowMs: Long = UpdateCaptureStateUseCase.POST_EVENT_WINDOW_MS,
) {
    data class Update(
        val sample: ObdSample,
        val stallDetected: Boolean,
        val captureCompleted: Boolean,
        val captureState: CaptureState,
    )

    private val rollingSamples = ArrayDeque<ObdSample>()
    private var activeCapture: MutableList<ObdSample>? = null
    private var latestCapture: List<ObdSample> = emptyList()
    private val updateCaptureState = UpdateCaptureStateUseCase(postEventWindowMs)

    val captureState: CaptureState get() = updateCaptureState.state

    fun record(incoming: ObdSample): Update {
        trimRollingWindow(incoming.timestampMs)
        val captureUpdate = updateCaptureState(incoming)
        val sample = if (captureUpdate.stallDetected) {
            incoming.copy(event = STALL_EVENT)
        } else {
            incoming
        }

        when {
            captureUpdate.stallDetected -> {
                activeCapture = rollingSamples.toMutableList().apply { add(sample) }
                rollingSamples.clear()
            }

            activeCapture != null -> {
                activeCapture!!.add(sample)
                if (captureUpdate.captureCompleted) {
                    latestCapture = activeCapture!!.toList()
                    activeCapture = null
                }
            }

            else -> rollingSamples.addLast(sample)
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
