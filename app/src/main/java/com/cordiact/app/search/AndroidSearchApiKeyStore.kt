package com.cordiact.app.search

import android.content.Context
import com.cordiact.app.security.AndroidSystemCredentials

/** Search-facing adapter, not a second credential storage or encryption implementation. */
class AndroidSearchApiKeyStore(context: Context) {
    private val credentials = AndroidSystemCredentials.get(context)
    fun get(providerId: String): String? = credentials.read("search", providerId)
    fun has(providerId: String): Boolean = !get(providerId).isNullOrBlank()
    fun put(providerId: String, apiKey: String) = credentials.set("search", providerId, apiKey)
    fun remove(providerId: String) = credentials.set("search", providerId, null)
}
