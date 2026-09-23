package com.nuvio.app.features.membership

import com.nuvio.app.core.storage.DesktopStorage
import java.io.File

internal actual object MemberAssetStorage {
    private const val accessPayloadKey = "access_payload"
    private const val backgroundCatalogPayloadKey = "profile_background_catalog_payload"

    private val store = DesktopStorage.store("nuvio_member_access")

    actual fun loadAccessPayload(): String? = store.getString(accessPayloadKey)

    actual fun saveAccessPayload(payload: String) {
        store.putString(accessPayloadKey, payload)
    }

    actual fun clearAccess() {
        store.remove(accessPayloadKey)
    }
}
