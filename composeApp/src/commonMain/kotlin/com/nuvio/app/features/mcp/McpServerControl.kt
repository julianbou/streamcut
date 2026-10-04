package com.nuvio.app.features.mcp

/**
 * The switch for letting an AI assistant drive StreamCut.
 *
 * When on, the desktop app runs a Model Context Protocol server on this
 * machine only, through which an assistant can search titles, find a line of
 * dialogue and cut a clip. Off by default: it lets another program start
 * downloads on the user's debrid account, which is not something to turn on
 * for them.
 *
 * Desktop only, like clip export itself. Mobile implementations are inert, so
 * the settings row needs no platform guard beyond [isSupported].
 */
internal expect object McpServerControl {
    val isSupported: Boolean

    /** What the user chose, whether or not the server managed to start. */
    fun isEnabled(): Boolean

    /** Saves the choice and starts or stops the server to match. */
    fun setEnabled(enabled: Boolean)

    /** Why the server is not running although it is enabled, or null when it is fine. */
    fun lastError(): String?

    fun endpointUrl(): String

    /** A ready-to-paste command that registers this server with Claude Code. */
    fun setupCommand(): String

    /**
     * The entry for Claude Desktop's configuration file, which is also what
     * Cowork uses. Null when this build has no launcher for a client to start.
     */
    fun desktopConfigSnippet(): String?

    /** The last thing an assistant did through the server, as one line; null when nothing has run. */
    fun lastActivity(): String?

    /** Shows the full record of what assistants have done, in whatever the system opens text with. */
    fun openActivityLog()
}
