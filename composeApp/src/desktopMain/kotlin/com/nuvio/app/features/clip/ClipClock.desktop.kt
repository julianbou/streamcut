package com.nuvio.app.features.clip

internal actual object ClipClock {
    actual fun nowEpochMs(): Long = System.currentTimeMillis()
}
