package com.nuvio.app.features.mcp

import co.touchlab.kermit.Logger
import com.nuvio.app.core.build.AppVersionConfig
import com.nuvio.app.core.storage.DesktopStorage
import com.nuvio.app.features.clip.ClipExtractor
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.security.SecureRandom

internal actual object McpServerControl {
    private val log = Logger.withTag("McpServer")

    // A machine store, so signing out does not silently change the token an
    // assistant was configured with. See DesktopStorage.MACHINE_STORES.
    private val store = DesktopStorage.store(StoreName)

    private var server: McpHttpServer? = null
    private var error: String? = null

    private val activityLog = McpActivityLog(DesktopStorage.rootDir.resolve("mcp-activity.log").toFile())

    actual val isSupported: Boolean = true

    actual fun isEnabled(): Boolean = store.getBoolean(KeyEnabled) == true

    @Synchronized
    actual fun setEnabled(enabled: Boolean) {
        store.putBoolean(KeyEnabled, enabled)
        if (enabled) start() else stop()
    }

    actual fun lastError(): String? = error

    actual fun endpointUrl(): String = "http://127.0.0.1:${port()}${McpHttpServer.Path}"

    // User scope, because the default registers the server only for whatever
    // folder the terminal happened to be in, and it then shows up nowhere else.
    //
    // Through the launcher when there is one: the relay reads the token
    // itself, so nothing secret is written into the client's configuration,
    // and it can start StreamCut when a call finds it closed. A build run from
    // Gradle has no launcher and falls back to the URL and the token.
    actual fun setupCommand(): String = launcherPath()
        ?.let { "claude mcp add --scope user streamcut -- \"$it\" ${McpStdioBridge.Flag}" }
        ?: (
            "claude mcp add --scope user --transport http streamcut ${endpointUrl()} " +
                "--header \"Authorization: Bearer ${token()}\""
            )

    actual fun lastActivity(): String? = activityLog.lastLine()

    actual fun openActivityLog() {
        // Opened even when empty: a blank file answers "has anything run", an error does not.
        runCatching { File(activityLog.path).apply { parentFile?.mkdirs(); createNewFile() } }
        ClipExtractor.openFile(activityLog.path)
    }

    /**
     * One member to paste inside `mcpServers` in Claude Desktop's configuration,
     * so it is the bare `"streamcut": {...}` pair rather than a whole object.
     * Null when run from Gradle: only a packaged app has a launcher a client
     * can start.
     */
    actual fun desktopConfigSnippet(): String? {
        val launcher = launcherPath() ?: return null
        val entry = buildJsonObject {
            put("command", launcher)
            put("args", JsonArray(listOf(JsonPrimitive(McpStdioBridge.Flag))))
        }
        return "\"streamcut\": $entry"
    }

    /** For [McpStdioBridge], which presents it to the server on the client's behalf. */
    fun accessToken(): String = token()

    fun dispatcher(tools: List<McpTool>, logCalls: Boolean = true): McpDispatcher = McpDispatcher(
        serverName = "streamcut",
        serverVersion = AppVersionConfig.DESKTOP_VERSION_NAME,
        instructions = Instructions,
        tools = tools,
        onToolCall = { tool, arguments, failed, tookMs ->
            if (logCalls) activityLog.record(tool, arguments.toString(), failed, tookMs)
        },
    )

    /**
     * Opens StreamCut and waits for its server, for [McpStdioBridge]. False
     * when the user has the setting off -- the app would open and still not
     * listen -- or when this is not a packaged build with a launcher to start.
     */
    fun launchAppAndWait(): Boolean {
        if (!isEnabled()) return false
        val launcher = launcherPath() ?: return false
        val bundle = File(launcher).parentFile?.parentFile?.parentFile?.takeIf { it.name.endsWith(".app") }
        // On macOS through `open`, in the background: a job that needs the
        // app should not take the screen from whoever is using the machine.
        val command = if (bundle != null) listOf("open", "-g", "-a", bundle.absolutePath) else listOf(launcher)
        val started = runCatching {
            ProcessBuilder(command)
                // Never inherited: this process's stdout is the protocol.
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start()
        }.isSuccess
        if (!started) return false

        val deadline = System.currentTimeMillis() + LaunchWaitMs
        while (System.currentTimeMillis() < deadline) {
            val listening = runCatching {
                Socket().use { it.connect(InetSocketAddress(InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)), port()), 500) }
            }.isSuccess
            if (listening) return true
            Thread.sleep(500)
        }
        return false
    }

    private fun launcherPath(): String? = System.getProperty("jpackage.app-path")?.takeIf { it.isNotBlank() }

    /** Called once at launch. Does nothing unless the user turned the server on. */
    @Synchronized
    fun startIfEnabled() {
        if (isEnabled()) start()
    }

    private fun start() {
        if (server != null) return
        val created = McpHttpServer(
            port = port(),
            token = token(),
            dispatcher = dispatcher(McpTools().all),
        )
        runCatching { created.start() }
            .onSuccess {
                server = created
                error = null
            }
            .onFailure { failure ->
                // Almost always the port: a second StreamCut, or something else
                // that got there first. The choice stays saved, so the next
                // launch tries again.
                log.w(failure) { "MCP server failed to start on port ${port()}" }
                error = failure.message ?: failure::class.simpleName
            }
    }

    private fun stop() {
        server?.stop()
        server = null
        error = null
    }

    private fun port(): Int =
        (System.getProperty("streamcut.mcp.port") ?: System.getenv("STREAMCUT_MCP_PORT"))
            ?.toIntOrNull()
            ?.takeIf { it in 1..65_535 }
            ?: DefaultPort

    /** Made on first use and kept, so a client configured once keeps working across launches. */
    private fun token(): String =
        store.getString(KeyToken)?.takeIf { it.isNotBlank() } ?: run {
            val bytes = ByteArray(24).also(SecureRandom()::nextBytes)
            bytes.joinToString("") { "%02x".format(it) }.also { store.putString(KeyToken, it) }
        }

    const val StoreName = "streamcut_mcp"
    private const val KeyEnabled = "enabled"
    private const val KeyToken = "token"

    /** Fixed rather than picked at launch: the client's configuration names it. */
    private const val DefaultPort = 47_800

    /** A cold start on a slow disk; past this the call is answered as unreachable. */
    private const val LaunchWaitMs = 40_000L

    private const val Instructions =
        "StreamCut cuts clips out of films and series. Typical flow: search_titles to get a title's " +
            "type and id (get_title lists a series' episodes), find_line to locate a moment by its " +
            "dialogue, list_streams to choose a source, create_clip with the range in milliseconds, then " +
            "get_clip with wait_seconds until it completes and reports the file. Subtitle timings can be " +
            "most of a minute off from a given stream: give find_line the stream_id to re-time its matches " +
            "against that stream, and use detect_cuts to end a clip exactly on a cut. For a moment nobody " +
            "speaks in, scan with get_contact_sheet and look closer with get_frames. Skip any stream that " +
            "list_streams marks with a warning, and call probe_stream first when a stream carries several " +
            "audio languages."
}
