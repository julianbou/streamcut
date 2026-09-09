package com.nuvio.app.features.membership

import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSFileManager
import platform.Foundation.NSHomeDirectory
import platform.Foundation.NSUserDefaults

@OptIn(ExperimentalForeignApi::class)
internal actual object MemberAssetStorage {
    private const val accessPayloadKey = "member_access_payload"
    private val legacyBrandingPath = "${NSHomeDirectory()}/Library/Application Support/NuvioMembership/branding.png"

    actual fun loadAccessPayload(): String? =
        NSUserDefaults.standardUserDefaults.stringForKey(accessPayloadKey)

    actual fun saveAccessPayload(payload: String) {
        NSUserDefaults.standardUserDefaults.setObject(payload, forKey = accessPayloadKey)
        NSFileManager.defaultManager.removeItemAtPath(legacyBrandingPath, null)
    }

    actual fun clearAccess() {
        NSUserDefaults.standardUserDefaults.removeObjectForKey(accessPayloadKey)
        NSFileManager.defaultManager.removeItemAtPath(legacyBrandingPath, null)
    }
}
