package com.nuvio.app.features.clip

internal expect object ClipClock {
    fun nowEpochMs(): Long
}
