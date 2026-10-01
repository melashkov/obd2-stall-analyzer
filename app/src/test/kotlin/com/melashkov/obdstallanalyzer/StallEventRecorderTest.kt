package com.melashkov.obdstallanalyzer

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
        assertEquals(StallEventRecorder.State.CAPTURING_AFTER_STALL, recorder.state)

        for (second in 71L..79L) {
            assertFalse(
                recorder.record(ObdSample(rpm = 0f, timestampMs = second * 1_000L)).captureCompleted,
            )
        }
        val completed = recorder.record(ObdSample(rpm = 0f, timestampMs = 80_000L))
        val samples = recorder.samplesForAnalysis()

        assertTrue(completed.captureCompleted)
        assertEquals(StallEventRecorder.State.CAPTURE_READY, recorder.state)
        assertEquals(10_000L, samples.first().timestampMs)
        assertEquals(80_000L, samples.last().timestampMs)
        assertEquals(1, samples.count { it.event == StallEventRecorder.STALL_EVENT })
        assertTrue(ObdCsv.build(samples, recorder.state).contains(",STALL_DETECTED\n"))
    }
}
