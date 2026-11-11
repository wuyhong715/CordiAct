package com.cordiact.app.model

import android.content.Context
import com.cordiact.app.security.AndroidSystemCredentials
import com.cordiact.model.providers.ApiKeyProvider

/** Compatibility facade only; all model credentials use the host-only vault namespace. */
class AndroidProviderApiKeyStore(context: Context) {
    private val credentials = AndroidSystemCredentials.get(context)
    fun provider(providerId: String): ApiKeyProvider = ApiKeyProvider { read(providerId) }
    fun read(providerId: String): String? = credentials.read("model", providerId)
    fun set(providerId: String, apiKey: String?) = credentials.set("model", providerId, apiKey)
    fun clear(providerId: String) = credentials.set("model", providerId, null)
}
