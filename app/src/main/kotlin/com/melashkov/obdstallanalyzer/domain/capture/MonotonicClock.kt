package com.melashkov.obdstallanalyzer.domain.capture

internal fun interface MonotonicClock {
    fun elapsedRealtimeMs(): Long
}
