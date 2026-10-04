package com.nuvio.app.features.mcp

import co.touchlab.kermit.Logger
import com.nuvio.app.core.build.AppVersionConfig
import com.nuvio.app.core.storage.DesktopStorage
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.security.SecureRandom

internal actual object McpServerControl {
    private val log = Logger.withTag("McpServer")

    // A machine store, so signing out does not silently change the token an
    // assistant was configured with. See DesktopStorage.MACHINE_STORES.
    private val store = DesktopStorage.store(StoreName)

    private var server: McpHttpServer? = null
    private var error: String? = null

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
    actual fun setupCommand(): String =
        "claude mcp add --scope user --transport http streamcut ${endpointUrl()} " +
            "--header \"Authorization: Bearer ${token()}\""

    /**
     * One member to paste inside `mcpServers` in Claude Desktop's configuration,
     * so it is the bare `"streamcut": {...}` pair rather than a whole object.
     * Null when run from Gradle: only a packaged app has a launcher a client
     * can start.
     */
    actual fun desktopConfigSnippet(): String? {
        val launcher = System.getProperty("jpackage.app-path")?.takeIf { it.isNotBlank() } ?: return null
        val entry = buildJsonObject {
            put("command", launcher)
            put("args", JsonArray(listOf(JsonPrimitive(McpStdioBridge.Flag))))
        }
        return "\"streamcut\": $entry"
    }

    /** For [McpStdioBridge], which presents it to the server on the client's behalf. */
    fun accessToken(): String = token()

    fun dispatcher(tools: List<McpTool>): McpDispatcher = McpDispatcher(
        serverName = "streamcut",
        serverVersion = AppVersionConfig.DESKTOP_VERSION_NAME,
        instructions = Instructions,
        tools = tools,
    )

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

    private const val Instructions =
        "StreamCut cuts clips out of films and series. Typical flow: search_titles to get a title's " +
            "type and id (get_title lists a series' episodes), find_line to locate a moment by its " +
            "dialogue, list_streams to choose a source, create_clip with the range in milliseconds, then " +
            "get_clip with wait_seconds until it completes and reports the file. Subtitle timings can be " +
            "a second or two off from a given stream, so pad a range taken from find_line, or look with " +
            "get_frames: it returns stills from a stream, which is how to place a cut on the exact shot."
}
