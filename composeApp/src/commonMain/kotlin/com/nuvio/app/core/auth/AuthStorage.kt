package com.nuvio.app.core.auth

internal expect object AuthStorage {
    fun loadAnonymousUserId(): String?
    fun saveAnonymousUserId(userId: String)
    fun clearAnonymousUserId()

    /**
     * Whether an account has ever been signed in on this install.
     *
     * The app gate uses it to tell "signed out, with a library on disk" from
     * "never signed in": the first is a lapsed session and should still reach
     * the local library, the second is a new install and belongs at the sign-in
     * screen. It used to be inferred from the cached profile list, so each
     * platform also reads that list's old key: an install that was signed in
     * before profiles were removed must not be sent back to the sign-in screen
     * by the upgrade.
     */
    fun loadHasSignedIn(): Boolean
    fun saveHasSignedIn()
    fun clearHasSignedIn()
}
