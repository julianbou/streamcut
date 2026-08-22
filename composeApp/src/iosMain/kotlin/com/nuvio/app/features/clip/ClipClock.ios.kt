package com.nuvio.app.features.clip

import kotlinx.cinterop.ExperimentalForeignApi
import platform.posix.time

internal actual object ClipClock {
    @OptIn(ExperimentalForeignApi::class)
    actual fun nowEpochMs(): Long = time(null) * 1000L
}
