package com.nuvio.app.features.clip

import com.nuvio.app.core.storage.DesktopStorage
import com.nuvio.app.core.storage.ProfileScopedKey

internal actual object ClipStorage {
    private val store = DesktopStorage.store("nuvio_clips")

    actual fun loadLibraryPayload(): String? =
        store.getString(ProfileScopedKey.of("clip_library"))

    actual fun saveLibraryPayload(payload: String) {
        store.putString(ProfileScopedKey.of("clip_library"), payload)
    }

    // The output folder is deliberately NOT profile-scoped: it is a machine
    // preference ("put my clips on the external drive"), not a per-profile one.
    actual fun loadOutputDir(): String? =
        store.getString("clip_output_dir")?.takeIf { it.isNotBlank() }

    actual fun saveOutputDir(path: String?) {
        store.putString("clip_output_dir", path?.takeIf { it.isNotBlank() })
    }
}
