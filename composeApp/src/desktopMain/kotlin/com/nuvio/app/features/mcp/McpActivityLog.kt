package com.nuvio.app.features.mcp

import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * What an assistant did through the server, one line a call.
 *
 * The server lets another program start downloads and write files, and it does
 * so while nobody is looking -- that is the point of it. This is the record of
 * what it was asked to do, kept on disk because the question "what did it do
 * last night" comes after the fact.
 *
 * Arguments are logged as given. They carry no secrets by construction: the
 * tools trade in short ids, never in links or tokens.
 */
internal class McpActivityLog(private val file: File) {

    @Synchronized
    fun record(tool: String, arguments: String, failed: Boolean, tookMs: Long) {
        runCatching {
            if (file.length() > MaxBytes) {
                // One previous file is kept: enough to look back past a rollover,
                // without the log growing for as long as the app is installed.
                val previous = File(file.parentFile, file.name + ".1")
                previous.delete()
                file.renameTo(previous)
            }
            file.parentFile?.mkdirs()
            file.appendText(line(LocalDateTime.now(), tool, arguments, failed, tookMs) + "\n")
        }
    }

    /** The most recent line, for the settings row; null when nothing has been called yet. */
    @Synchronized
    fun lastLine(): String? = runCatching {
        file.takeIf { it.length() > 0L }?.useLines { lines -> lines.lastOrNull { it.isNotBlank() } }
    }.getOrNull()

    val path: String get() = file.absolutePath

    companion object {
        private const val MaxBytes = 512 * 1024L
        private const val MaxArgumentChars = 300
        private val Stamp = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

        fun line(at: LocalDateTime, tool: String, arguments: String, failed: Boolean, tookMs: Long): String {
            val shown = arguments.replace(Regex("\\s+"), " ").let {
                if (it.length > MaxArgumentChars) it.take(MaxArgumentChars) + "…" else it
            }
            return "${at.format(Stamp)}  ${if (failed) "FAILED" else "ok    "}  $tool  ${tookMs}ms  $shown"
        }
    }
}
