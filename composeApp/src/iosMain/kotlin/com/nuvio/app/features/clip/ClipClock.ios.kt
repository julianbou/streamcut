package com.nuvio.app.features.clip

import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSDate
import platform.Foundation.NSDateFormatter
import platform.Foundation.dateWithTimeIntervalSince1970
import platform.posix.time

internal actual object ClipClock {
    @OptIn(ExperimentalForeignApi::class)
    actual fun nowEpochMs(): Long = time(null) * 1000L

    actual fun localStamp(epochMs: Long): ClipStamp {
        val date = NSDate.dateWithTimeIntervalSince1970(epochMs / 1000.0)
        return ClipStamp(date = format("yyyy-MM-dd", date), time = format("HH-mm-ss", date))
    }

    private fun format(pattern: String, date: NSDate): String =
        NSDateFormatter().apply { dateFormat = pattern }.stringFromDate(date)
}
