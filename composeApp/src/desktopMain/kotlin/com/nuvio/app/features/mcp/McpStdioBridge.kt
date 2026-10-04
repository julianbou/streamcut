package com.nuvio.app.features.mcp

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.io.BufferedReader
import java.io.IOException
import java.io.PrintStream
import java.net.HttpURLConnection
import java.net.URI

/**
 * MCP's stdio transport, for clients that start a server as a child process
 * and cannot be pointed at a URL -- Claude Desktop, and through it Cowork.
 *
 * It is the app's own launcher run with [Flag]: no window, just a relay that
 * reads one JSON-RPC message per line and passes it to the HTTP server inside
 * the StreamCut that is already open. A relay rather than a second server
 * because the work has to happen in that process: it owns the clip queue, and
 * two apps writing the same stores is the thing sign-out and the clip library
 * were written to avoid.
 *
 * The relay reads the port and token from the same store the server does, so
 * a client's configuration is one command line and holds no secret.
 */
internal class McpStdioBridge(
    private val endpoint: String,
    private val token: String,
    /** Answers when the app cannot be reached, so the client still sees a working server. */
    private val offline: McpDispatcher,
    /**
     * Starts the app and waits for its server; true once it is listening.
     * What lets a job scheduled for the small hours run without someone having
     * left StreamCut open for it.
     */
    private val wake: () -> Boolean = { false },
) {
    /** When waking the app last failed, so a client that keeps calling does not relaunch it on every call. */
    private var wakeFailedAt = 0L

    fun relay(input: BufferedReader, output: PrintStream) {
        while (true) {
            val line = input.readLine() ?: return
            if (line.isBlank()) continue
            val reply = forward(line) ?: continue
            output.println(reply)
            output.flush()
        }
    }

    /** The server's reply to [message], or null when it owes none. */
    private fun forward(message: String): String? {
        val parsed: JsonElement = runCatching { Json.parseToJsonElement(message) }.getOrElse {
            return McpDispatcher.errorReply(JsonNull, McpDispatcher.ParseError, "Parse error").toString()
        }
        return try {
            post(message)
        } catch (error: IOException) {
            System.err.println("streamcut: $endpoint unreachable: $error")
            // Only for a tool call. Connecting and listing tools happen every
            // time the client starts, and opening StreamCut each time someone
            // opens their assistant would be the app launching itself.
            if (parsed.isToolCall() && wakeApp()) {
                try {
                    return post(message)
                } catch (retry: IOException) {
                    System.err.println("streamcut: still unreachable after launching the app: $retry")
                }
            }
            // The client keeps its connection and its tool list; only calling
            // a tool says what is wrong, where the model can read it and tell
            // the user.
            runBlocking { offline.handle(parsed) }?.toString()
        }
    }

    private fun wakeApp(): Boolean {
        if (System.currentTimeMillis() - wakeFailedAt < WakeRetryAfterMs) return false
        return wake().also { woke -> if (!woke) wakeFailedAt = System.currentTimeMillis() }
    }

    private fun JsonElement.isToolCall(): Boolean =
        ((this as? JsonObject)?.get("method") as? JsonPrimitive)?.contentOrNull == "tools/call"

    private fun post(message: String): String? {
        val connection = URI(endpoint).toURL().openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.connectTimeout = ConnectTimeoutMs
        connection.readTimeout = ReadTimeoutMs
        connection.doOutput = true
        connection.setRequestProperty("Authorization", "Bearer $token")
        connection.setRequestProperty("Content-Type", "application/json")
        connection.outputStream.use { it.write(message.toByteArray()) }
        return when (val status = connection.responseCode) {
            200 -> connection.inputStream.use { it.readBytes().decodeToString() }
            202 -> null
            // A JSON-RPC error still comes with a body worth passing on.
            400 -> connection.errorStream?.use { it.readBytes().decodeToString() }
            else -> throw IOException("StreamCut answered HTTP $status")
        }
    }

    companion object {
        const val Flag = "--mcp-stdio"

        private const val ConnectTimeoutMs = 2_000

        /** Past the server's own request limit, so it is the server that decides a call took too long. */
        private const val ReadTimeoutMs = 130_000

        private const val WakeRetryAfterMs = 60_000L

        private const val NotRunning =
            "StreamCut is not reachable and could not be started. Ask the user to open StreamCut and check " +
                "that Settings > Clips > \"Let an AI assistant use StreamCut\" is on, then try again."

        /** Runs the relay on this process's stdin and stdout until the client closes the pipe. */
        fun runOnStandardStreams() {
            // The protocol owns stdout: one stray log line there and the client
            // drops the connection. Everything else is sent to stderr.
            val protocolOut = System.out
            System.setOut(System.err)
            McpStdioBridge(
                endpoint = McpServerControl.endpointUrl(),
                token = McpServerControl.accessToken(),
                offline = offlineDispatcher(),
                wake = McpServerControl::launchAppAndWait,
            ).relay(System.`in`.bufferedReader(), protocolOut)
        }

        /** The real tool list, with every tool answering that the app is not there. */
        fun offlineDispatcher(): McpDispatcher = McpServerControl.dispatcher(
            tools = McpTools().all.map { tool ->
                McpTool(tool.name, tool.description, tool.inputSchema, tool.effect) { throw McpToolException(NotRunning) }
            },
            // Nothing happened, so there is nothing for the record.
            logCalls = false,
        )
    }
}
