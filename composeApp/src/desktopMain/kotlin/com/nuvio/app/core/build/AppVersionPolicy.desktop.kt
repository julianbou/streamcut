package com.nuvio.app.core.build

actual object AppVersionPolicy {
    actual val displayVersionName: String = AppVersionConfig.DESKTOP_VERSION_NAME
    actual val displayVersionCode: Int = AppVersionConfig.DESKTOP_VERSION_CODE
    // Always shown: the About footer credits the Nuvio Desktop release this fork
    // is built from. VERSION_NAME is the shared *mobile* version, which is why
    // the footer used to read "Based on Nuvio 0.4.7" on a desktop build.
    actual val basedOnVersionName: String? =
        AppVersionConfig.UPSTREAM_DESKTOP_VERSION_NAME.takeIf { it.isNotBlank() }
    actual val userAgentAppName: String = "NuvioDesktop"
}
