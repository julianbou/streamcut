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

    // A machine preference too, for the same reason as the folder: the naming
    // that suits how you file clips does not change per profile.
    actual fun loadFilenameTemplate(): String? =
        store.getString("clip_filename_template")?.takeIf { it.isNotBlank() }

    actual fun saveFilenameTemplate(template: String?) {
        store.putString("clip_filename_template", template?.takeIf { it.isNotBlank() })
    }

    // Machine preference as well: it describes the shape of the folder on this
    // disk, which is not something a second person on the same machine would
    // want to see change under them.
    actual fun loadGroupByTitle(): Boolean =
        store.getString("clip_group_by_title") == "1"

    actual fun saveGroupByTitle(enabled: Boolean) {
        store.putString("clip_group_by_title", if (enabled) "1" else null)
    }
}
