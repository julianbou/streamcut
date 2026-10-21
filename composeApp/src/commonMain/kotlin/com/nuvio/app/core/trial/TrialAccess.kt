package com.nuvio.app.core.trial

import co.touchlab.kermit.Logger
import com.nuvio.app.core.auth.AuthRepository
import com.nuvio.app.core.network.SupabaseProvider
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.rpc
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlin.concurrent.Volatile
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.getString

@Serializable
private data class TrialStatusRow(
    @SerialName("is_trial") val isTrial: Boolean = false,
    val active: Boolean = true,
)

/**
 * Trial accounts: ordinary email accounts the owner hands out with addons
 * preloaded as hidden (see supabase/patch-trial-accounts.sql and
 * scripts/trials/).
 *
 * On every sign-in this asks the server whether the account is a trial and
 * whether it is still live. An ended trial is signed out. A live one reports
 * what it does -- app opens, streams played, clips exported -- for the owner's
 * dashboard. Regular accounts report nothing, and when the server has no trial
 * functions at all every call here fails quietly and changes nothing.
 */
object TrialAccess {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val log = Logger.withTag("TrialAccess")

    @Volatile private var checkedUserId: String? = null
    @Volatile private var isTrial = false

    /** Called whenever a remote (non-anonymous) account becomes the signed-in one. */
    fun onSignedIn(userId: String) {
        if (userId.isBlank() || checkedUserId == userId) return
        checkedUserId = userId
        scope.launch {
            val status = runCatching {
                SupabaseProvider.client.postgrest.rpc("trial_status")
                    .decodeList<TrialStatusRow>()
                    .firstOrNull()
            }.onFailure { e -> log.w(e) { "trial_status failed" } }
                .getOrNull()
                ?: return@launch

            isTrial = status.isTrial
            if (status.isTrial && !status.active) {
                log.i { "Trial ended; signing out" }
                isTrial = false
                AuthRepository.signOut()
                AuthRepository.showError(getString(Res.string.trial_ended))
                return@launch
            }
            if (status.isTrial) record("app_open")
        }
    }

    fun onSignedOut() {
        checkedUserId = null
        isTrial = false
    }

    fun logStreamPlay(
        title: String,
        contentType: String?,
        videoId: String?,
        providerName: String,
        seasonNumber: Int?,
        episodeNumber: Int?,
    ) = record("stream_play") {
        put("title", title)
        contentType?.let { put("type", it) }
        videoId?.let { put("id", it) }
        put("provider", providerName)
        seasonNumber?.let { put("season", it) }
        episodeNumber?.let { put("episode", it) }
    }

    fun logClipExport(title: String, durationMs: Long) = record("clip_export") {
        put("title", title)
        put("seconds", durationMs / 1000)
    }

    private fun record(kind: String, detail: JsonObjectBuilder.() -> Unit = {}) {
        if (!isTrial) return
        val params = buildJsonObject {
            put("p_kind", kind)
            put("p_detail", buildJsonObject(detail))
        }
        scope.launch {
            runCatching { SupabaseProvider.client.postgrest.rpc("log_usage", params) }
                .onFailure { e -> log.w(e) { "log_usage($kind) failed" } }
        }
    }
}
