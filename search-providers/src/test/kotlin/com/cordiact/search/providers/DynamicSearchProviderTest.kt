package com.cordiact.search.providers

import com.cordiact.core.search.*
import kotlin.test.*

class DynamicSearchProviderTest {
    @Test fun endpointReplacementTakesEffectWithoutRegistryRebuild() {
        var value = "old-endpoint"
        val dynamic = DynamicSearchProvider {
            val endpoint = value
            object : SearchProvider {
                override val id = "demo"
                override val native = false
                override fun available() = endpoint.isNotEmpty()
                override fun ready(route: ModelRoute?) = available()
                override fun search(request: SearchRequest, signal: CancellationSignal) = SearchResult(sources = listOf(SearchSource("https://example.test", title = endpoint)))
            }
        }
        val request = SearchRequest("test", 3)
        assertEquals("old-endpoint", dynamic.search(request).sources.single().title)
        value = "replacement-endpoint"
        assertEquals("replacement-endpoint", dynamic.search(request).sources.single().title)
    }
}
