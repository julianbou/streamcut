package com.nuvio.app.features.clip

import java.util.Calendar
import java.util.Locale

internal actual object ClipClock {
    actual fun nowEpochMs(): Long = System.currentTimeMillis()

    // Calendar rather than java.time: this module still supports API levels
    // below 26, where java.time needs desugaring.
    actual fun localStamp(epochMs: Long): ClipStamp {
        val calendar = Calendar.getInstance().apply { timeInMillis = epochMs }
        return ClipStamp(
            date = String.format(
                Locale.US,
                "%04d-%02d-%02d",
                calendar.get(Calendar.YEAR),
                calendar.get(Calendar.MONTH) + 1,
                calendar.get(Calendar.DAY_OF_MONTH),
            ),
            time = String.format(
                Locale.US,
                "%02d-%02d-%02d",
                calendar.get(Calendar.HOUR_OF_DAY),
                calendar.get(Calendar.MINUTE),
                calendar.get(Calendar.SECOND),
            ),
        )
    }
}
