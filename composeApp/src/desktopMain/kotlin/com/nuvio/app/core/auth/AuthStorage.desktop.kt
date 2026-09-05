package com.nuvio.app.core.auth

import com.nuvio.app.core.storage.DesktopStorage

internal actual object AuthStorage {
    private val store = DesktopStorage.store("nuvio_auth")

    /** The cached profile list this flag replaced. Read only, never written. */
    private val legacyProfileStore = DesktopStorage.store("nuvio_profiles")

    actual fun loadAnonymousUserId(): String? =
        store.getString("anonymous_user_id")

    actual fun saveAnonymousUserId(userId: String) {
        store.putString("anonymous_user_id", userId)
    }

    actual fun clearAnonymousUserId() {
        store.remove("anonymous_user_id")
    }

    actual fun loadHasSignedIn(): Boolean =
        store.getString("has_signed_in") == "1" ||
            legacyProfileStore.getString("profiles") != null

    actual fun saveHasSignedIn() {
        store.putString("has_signed_in", "1")
    }

    actual fun clearHasSignedIn() {
        store.remove("has_signed_in")
    }
}
