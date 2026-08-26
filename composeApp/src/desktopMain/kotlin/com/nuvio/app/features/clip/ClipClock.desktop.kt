package com.nuvio.app.features.clip

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

internal actual object ClipClock {
    private val dateFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd")
    private val timeFormat = DateTimeFormatter.ofPattern("HH-mm-ss")

    actual fun nowEpochMs(): Long = System.currentTimeMillis()

    actual fun localStamp(epochMs: Long): ClipStamp {
        val local = Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault())
        return ClipStamp(date = dateFormat.format(local), time = timeFormat.format(local))
    }
}
