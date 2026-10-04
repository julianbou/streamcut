package com.nuvio.app.features.mcp

internal actual object McpServerControl {
    actual val isSupported: Boolean = false

    actual fun isEnabled(): Boolean = false

    actual fun setEnabled(enabled: Boolean) = Unit

    actual fun lastError(): String? = null

    actual fun endpointUrl(): String = ""

    actual fun setupCommand(): String = ""

    actual fun desktopConfigSnippet(): String? = null

    actual fun lastActivity(): String? = null

    actual fun openActivityLog() = Unit
}
