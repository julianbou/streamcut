package com.nuvio.app.features.membership

import android.content.Context
import android.content.SharedPreferences
import java.io.File

internal actual object MemberAssetStorage {
    private const val preferencesName = "nuvio_member_access"
    private const val accessPayloadKey = "access_payload"
    private const val backgroundCatalogPayloadKey = "background_catalog_payload"
    private var preferences: SharedPreferences? = null
    private var legacyBrandingFile: File? = null

    fun initialize(context: Context) {
        preferences = context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
        legacyBrandingFile = context.filesDir.resolve("membership/branding.png")
    }

    actual fun loadAccessPayload(): String? = preferences?.getString(accessPayloadKey, null)

    actual fun saveAccessPayload(payload: String) {
        preferences?.edit()?.putString(accessPayloadKey, payload)?.apply()
        legacyBrandingFile?.delete()
    }

    actual fun clearAccess() {
        preferences?.edit()?.remove(accessPayloadKey)?.apply()
        legacyBrandingFile?.delete()
    }
}
