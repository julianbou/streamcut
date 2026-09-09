package com.nuvio.app.core.storage

/**
 * Suffixes a preference key with the storage scope it belongs to.
 *
 * The app used to carry several user profiles, and every stored key was
 * suffixed with the active one. Profiles are gone, but the suffix is not: the
 * keys already written to disk carry [ScopeId], and dropping it would leave a
 * working install unable to find its own library, watch progress, addons and
 * settings. So the scope survives its feature, as a constant.
 */
object ProfileScopedKey {

    /** The one scope everything is stored under; the id the removed default profile had. */
    const val ScopeId: Int = 1

    /**
     * Scopes an install made before profiles were removed may also have written
     * under. Only wiping [ScopeId] would leave the other five behind for good,
     * since nothing can reach them any more.
     */
    val LegacyScopeIds: IntRange = 1..6

    fun of(baseKey: String): String = "${baseKey}_$ScopeId"

    /**
     * Kept for the sync layer, which still passes the scope it captured a
     * request under so a late response cannot be applied to a different one.
     */
    fun of(baseKey: String, profileId: Int): String = "${baseKey}_$profileId"
}
