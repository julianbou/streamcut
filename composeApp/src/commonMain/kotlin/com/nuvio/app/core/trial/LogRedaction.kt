package com.nuvio.app.core.trial

import co.touchlab.kermit.LogWriter
import co.touchlab.kermit.Logger
import co.touchlab.kermit.Severity
import kotlin.concurrent.Volatile
import kotlin.jvm.Synchronized

/**
 * Keeps a trial account's hidden addon URLs out of the logs.
 *
 * An AIOStreams manifest URL carries the config's id and password, so anyone
 * who reads it can use that config anywhere. The app logs addon and request
 * URLs in many places; rather than guard each one, every log writer is
 * wrapped once and each message has the hidden addons' base URLs replaced by
 * the bare host (`https://host/` followed by asterisks).
 */
object LogRedaction {
    @Volatile private var bases: List<String> = emptyList()
    private var installed = false

    /** Registers [manifestUrls] as secret. Call before anything logs them. */
    fun hide(manifestUrls: Collection<String>) {
        val added = manifestUrls
            .map { it.substringBefore("?").removeSuffix("/manifest.json").trimEnd('/') }
            .filter { it.substringAfter("://").contains('/') }
        if (added.isEmpty()) return
        // Longest first, so a base never leaves part of a longer one exposed.
        bases = (bases + added).distinct().sortedByDescending { it.length }
        install()
    }

    fun redact(message: String): String {
        var result = message
        for (base in bases) {
            if (base in result) result = result.replace(base, mask(base))
        }
        return result
    }

    private fun mask(base: String): String {
        val scheme = base.substringBefore("://", "")
        val host = base.substringAfter("://").substringBefore('/')
        return "$scheme://$host/***"
    }

    @Synchronized
    private fun install() {
        if (installed) return
        installed = true
        Logger.setLogWriters(Logger.config.logWriterList.map(::RedactingLogWriter))
    }
}

private class RedactingLogWriter(private val inner: LogWriter) : LogWriter() {
    override fun isLoggable(tag: String, severity: Severity): Boolean =
        inner.isLoggable(tag, severity)

    override fun log(severity: Severity, message: String, tag: String, throwable: Throwable?) {
        inner.log(severity, LogRedaction.redact(message), tag, throwable)
    }
}
