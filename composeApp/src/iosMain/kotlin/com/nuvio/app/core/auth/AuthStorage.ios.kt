package com.nuvio.app.core.auth

import platform.Foundation.NSUserDefaults

actual object AuthStorage {
    private const val KEY_ANONYMOUS_USER_ID = "anonymous_user_id"
    private const val KEY_HAS_SIGNED_IN = "has_signed_in"

    actual fun loadAnonymousUserId(): String? =
        NSUserDefaults.standardUserDefaults.stringForKey(KEY_ANONYMOUS_USER_ID)

    actual fun saveAnonymousUserId(userId: String) {
        NSUserDefaults.standardUserDefaults.setObject(userId, forKey = KEY_ANONYMOUS_USER_ID)
    }

    actual fun clearAnonymousUserId() {
        NSUserDefaults.standardUserDefaults.removeObjectForKey(KEY_ANONYMOUS_USER_ID)
    }

    actual fun loadHasSignedIn(): Boolean =
        NSUserDefaults.standardUserDefaults.boolForKey(KEY_HAS_SIGNED_IN) ||
            // The cached profile list this flag replaced. Read only, never written.
            NSUserDefaults.standardUserDefaults.stringForKey("profile_payload") != null

    actual fun saveHasSignedIn() {
        NSUserDefaults.standardUserDefaults.setBool(true, forKey = KEY_HAS_SIGNED_IN)
    }

    actual fun clearHasSignedIn() {
        NSUserDefaults.standardUserDefaults.removeObjectForKey(KEY_HAS_SIGNED_IN)
    }
}
