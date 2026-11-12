package com.cordiact.search.providers

import com.cordiact.core.search.*

/** Recreate a cheap provider configuration per call; endpoint changes need no app restart. */
class DynamicSearchProvider(private val factory: () -> SearchProvider) : SearchProvider {
    override val id get() = factory().id
    override val native get() = factory().native
    override val pricing get() = factory().pricing
    override val billable get() = factory().billable
    override fun available() = factory().available()
    override fun ready(route: ModelRoute?) = factory().ready(route)
    override fun search(request: SearchRequest, signal: CancellationSignal) = factory().search(request, signal)
}
