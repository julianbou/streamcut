package com.nuvio.app.core.auth

import android.content.Context
import android.content.SharedPreferences

actual object AuthStorage {
    private const val PREFS_NAME = "nuvio_auth"
    private const val KEY_ANONYMOUS_USER_ID = "anonymous_user_id"
    private const val KEY_HAS_SIGNED_IN = "has_signed_in"

    private var preferences: SharedPreferences? = null

    /** The cached profile list this flag replaced. Read only, never written. */
    private var legacyProfilePreferences: SharedPreferences? = null

    fun initialize(context: Context) {
        preferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        legacyProfilePreferences =
            context.getSharedPreferences("nuvio_profile_cache", Context.MODE_PRIVATE)
    }

    actual fun loadAnonymousUserId(): String? =
        preferences?.getString(KEY_ANONYMOUS_USER_ID, null)

    actual fun saveAnonymousUserId(userId: String) {
        preferences?.edit()?.putString(KEY_ANONYMOUS_USER_ID, userId)?.apply()
    }

    actual fun clearAnonymousUserId() {
        preferences?.edit()?.remove(KEY_ANONYMOUS_USER_ID)?.apply()
    }

    actual fun loadHasSignedIn(): Boolean =
        preferences?.getBoolean(KEY_HAS_SIGNED_IN, false) == true ||
            legacyProfilePreferences?.getString("profile_payload", null) != null

    actual fun saveHasSignedIn() {
        preferences?.edit()?.putBoolean(KEY_HAS_SIGNED_IN, true)?.apply()
    }

    actual fun clearHasSignedIn() {
        preferences?.edit()?.remove(KEY_HAS_SIGNED_IN)?.apply()
    }
}
