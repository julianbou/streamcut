package com.nuvio.app.features.membership

internal expect object MemberAssetStorage {
    fun loadAccessPayload(): String?
    fun saveAccessPayload(payload: String)
    fun clearAccess()
}
