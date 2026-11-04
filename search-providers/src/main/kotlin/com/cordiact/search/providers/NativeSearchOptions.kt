package com.cordiact.search.providers

/**
 * Vendor-specific knobs for native search, read once per call.
 *
 * Every field is nullable or defaulted: a provider only sends the parameters its
 * own API accepts, and an unset option means "let the vendor decide". Keeping them
 * in one object avoids a constructor per vendor growing a parameter list nobody
 * can read, while still letting the settings layer drive them.
 */
data class NativeSearchOptions(
    /** Anthropic / DeepSeek `max_uses`: how many searches one call may perform. */
    val maxUses: Int = 5,
    /** Anthropic / DeepSeek output budget for the auxiliary turn. */
    val maxTokens: Int = 4096,
    /** Anthropic domain filters, passed through verbatim. */
    val allowedDomains: List<String> = emptyList(),
    val blockedDomains: List<String> = emptyList(),
    /** OpenAI `search_context_size`: low | medium | high. */
    val searchContextSize: String = "medium",
    /** Gemini `url_context`: also read URLs mentioned in the prompt. */
    val geminiUrlContext: Boolean = false,
    /** Approximate user location, as a vendor-neutral hint. */
    val userLocation: SearchUserLocation? = null,
) {
    init {
        require(maxUses in 1..20) { "maxUses must be within 1..20" }
        require(maxTokens in 256..16_384) { "maxTokens must be within 256..16384" }
        require(searchContextSize in CONTEXT_SIZES) { "searchContextSize must be one of $CONTEXT_SIZES" }
        require(allowedDomains.size <= MAX_DOMAINS && blockedDomains.size <= MAX_DOMAINS) {
            "At most $MAX_DOMAINS domains may be filtered"
        }
        require(allowedDomains.isEmpty() || blockedDomains.isEmpty()) {
            "A request cannot both allow and block domains"
        }
    }

    companion object {
        val CONTEXT_SIZES = setOf("low", "medium", "high")
        const val MAX_DOMAINS = 20
        val DEFAULT = NativeSearchOptions()
    }
}

/**
 * Coarse location hint.
 *
 * Only country and city are carried: native search APIs take city/country/region,
 * and precise coordinates would be a privacy cost with no search benefit.
 */
data class SearchUserLocation(
    val city: String? = null,
    val region: String? = null,
    val country: String? = null,
    /** IANA timezone, for example "Asia/Shanghai". */
    val timezone: String? = null,
) {
    val isEmpty: Boolean get() = city == null && region == null && country == null && timezone == null
}
