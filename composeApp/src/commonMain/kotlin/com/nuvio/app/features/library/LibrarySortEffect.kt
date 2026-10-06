package com.nuvio.app.features.library

import com.nuvio.app.core.storage.ProfileScopedKey
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.core.ui.NuvioToastController
import com.nuvio.app.core.ui.ScreenActivityEffect
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.library_error_sort_failed
import org.jetbrains.compose.resources.getString

@Composable
internal fun rememberLibraryProviderOrders(
    sourceMode: LibrarySourceMode,
    listKeys: List<String>,
    sortOption: LibrarySortOption,
): LibraryProviderOrders {
    val profileId = ProfileScopedKey.ScopeId
    var orders by remember(profileId, sourceMode, listKeys, sortOption) { mutableStateOf(LibraryProviderOrders()) }
    ScreenActivityEffect(profileId, sourceMode, listKeys, sortOption) { active ->
        if (!active) return@ScreenActivityEffect
        observeLibraryProviderOrders(sourceMode.librarySorter(), listKeys, sortOption).collect { orders = it }
    }
    ScreenActivityEffect(orders.failed) { active ->
        if (active && orders.failed) {
            val message = getString(Res.string.library_error_sort_failed)
            LibraryDisplaySettingsRepository.setSortOption(LibrarySortOption.DEFAULT)
            NuvioToastController.show(message)
        }
    }
    return orders
}
