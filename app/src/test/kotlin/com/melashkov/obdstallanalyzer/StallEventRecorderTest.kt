package com.melashkov.obdstallanalyzer

import com.melashkov.obdstallanalyzer.data.obd.ObdCsv
import com.melashkov.obdstallanalyzer.domain.capture.CaptureState
import com.melashkov.obdstallanalyzer.domain.capture.StallEventRecorder
import com.melashkov.obdstallanalyzer.domain.capture.UpdateCaptureStateUseCase
import com.melashkov.obdstallanalyzer.domain.model.ObdSample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StallEventRecorderTest {
    @Test
    fun retainsSixtySecondsBeforeAndTenSecondsAfterExplicitMarker() {
        val recorder = StallEventRecorder()

        for (second in 0L until 70L) {
            recorder.record(ObdSample(rpm = 800f, timestampMs = second * 1_000L))
        }
        val event = recorder.record(ObdSample(rpm = 0f, timestampMs = 70_000L))

        assertTrue(event.stallDetected)
        assertEquals(StallEventRecorder.STALL_EVENT, event.sample.event)
        assertEquals(
            CaptureState(
                phase = CaptureState.Phase.CAPTURING,
                secondsRemaining = 10L,
            ),
            recorder.captureState,
        )

        for (second in 71L..79L) {
            assertFalse(
                recorder.record(ObdSample(rpm = 0f, timestampMs = second * 1_000L)).captureCompleted,
            )
        }
        val completed = recorder.record(ObdSample(rpm = 0f, timestampMs = 80_000L))
        val samples = recorder.samplesForAnalysis()

        assertTrue(completed.captureCompleted)
        assertEquals(
            CaptureState.Phase.READY,
            recorder.captureState.phase,
        )
        assertEquals(10_000L, samples.first().timestampMs)
        assertEquals(80_000L, samples.last().timestampMs)
        assertEquals(1, samples.count { it.event == StallEventRecorder.STALL_EVENT })
        assertTrue(ObdCsv.build(samples, recorder.captureState).contains(",STALL_DETECTED\n"))
    }

    @Test
    fun captureStateUseCaseOwnsArmingAndCountdownRules() {
        val updateCaptureState = UpdateCaptureStateUseCase()

        assertEquals(
            CaptureState.Phase.WAITING,
            updateCaptureState(ObdSample(rpm = 699f, timestampMs = 0L)).state.phase,
        )
        assertEquals(
            CaptureState.Phase.ARMED,
            updateCaptureState(ObdSample(rpm = 700f, timestampMs = 1_000L)).state.phase,
        )
        assertEquals(
            CaptureState.Phase.ARMED,
            updateCaptureState(ObdSample(rpm = 650f, timestampMs = 2_000L)).state.phase,
        )

        val stall = updateCaptureState(ObdSample(rpm = 0f, timestampMs = 3_000L))
        assertEquals(CaptureState.Phase.CAPTURING, stall.state.phase)
        assertEquals(10L, stall.state.secondsRemaining)

        val countdown = updateCaptureState(ObdSample(rpm = 0f, timestampMs = 3_001L))
        assertEquals(10L, countdown.state.secondsRemaining)
        assertEquals(
            1L,
            updateCaptureState(ObdSample(rpm = 0f, timestampMs = 12_999L))
                .state.secondsRemaining,
        )
    }
}
