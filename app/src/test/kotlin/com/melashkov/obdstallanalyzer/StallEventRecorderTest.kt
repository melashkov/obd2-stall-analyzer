package com.melashkov.obdstallanalyzer

import com.melashkov.obdstallanalyzer.data.obd.ObdCsv
import com.melashkov.obdstallanalyzer.domain.capture.CaptureState
import com.melashkov.obdstallanalyzer.domain.capture.MonotonicClock
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
        val clock = FakeMonotonicClock()
        val recorder = StallEventRecorder(clock)

        for (second in 0L until 70L) {
            clock.nowMs = second * 1_000L
            recorder.record(ObdSample(rpm = 800f, timestampMs = second * 1_000L))
        }
        clock.nowMs = 70_000L
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
            clock.nowMs = second * 1_000L
            assertFalse(
                recorder.record(ObdSample(rpm = 0f, timestampMs = second * 1_000L)).captureCompleted,
            )
        }
        clock.nowMs = 80_000L
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
            updateCaptureState(rpm = 699f, elapsedRealtimeMs = 0L).state.phase,
        )
        assertEquals(
            CaptureState.Phase.ARMED,
            updateCaptureState(rpm = 700f, elapsedRealtimeMs = 1_000L).state.phase,
        )
        assertEquals(
            CaptureState.Phase.ARMED,
            updateCaptureState(rpm = 650f, elapsedRealtimeMs = 2_000L).state.phase,
        )

        val stall = updateCaptureState(rpm = 0f, elapsedRealtimeMs = 3_000L)
        assertEquals(CaptureState.Phase.CAPTURING, stall.state.phase)
        assertEquals(10L, stall.state.secondsRemaining)

        val countdown = updateCaptureState(rpm = 0f, elapsedRealtimeMs = 3_001L)
        assertEquals(10L, countdown.state.secondsRemaining)
        assertEquals(
            1L,
            updateCaptureState(rpm = 0f, elapsedRealtimeMs = 12_999L)
                .state.secondsRemaining,
        )
    }

    @Test
    fun wallClockChangesDoNotAffectCaptureDurations() {
        val clock = FakeMonotonicClock()
        val recorder = StallEventRecorder(clock)

        recorder.record(ObdSample(rpm = 800f, timestampMs = 1_000_000L))
        clock.nowMs = 1_000L
        recorder.record(ObdSample(rpm = 0f, timestampMs = 1L))

        clock.nowMs = 10_999L
        val stillCapturing = recorder.record(ObdSample(rpm = 0f, timestampMs = Long.MAX_VALUE))
        assertFalse(stillCapturing.captureCompleted)
        assertEquals(1L, stillCapturing.captureState.secondsRemaining)

        clock.nowMs = 11_000L
        val completed = recorder.record(ObdSample(rpm = 0f, timestampMs = 0L))
        assertTrue(completed.captureCompleted)
    }

    @Test
    fun wallClockChangesDoNotAffectRollingWindow() {
        val clock = FakeMonotonicClock()
        val recorder = StallEventRecorder(clock)

        recorder.record(ObdSample(rpm = 0f, timestampMs = 1_000_000L))
        clock.nowMs = 59_000L
        recorder.record(ObdSample(rpm = 0f, timestampMs = 1L))
        assertEquals(2, recorder.samplesForAnalysis().size)

        clock.nowMs = 61_000L
        recorder.record(ObdSample(rpm = 0f, timestampMs = Long.MAX_VALUE))
        val retained = recorder.samplesForAnalysis()
        assertEquals(2, retained.size)
        assertEquals(1L, retained.first().timestampMs)
    }

    private class FakeMonotonicClock(
        var nowMs: Long = 0L,
    ) : MonotonicClock {
        override fun elapsedRealtimeMs(): Long = nowMs
    }
}
