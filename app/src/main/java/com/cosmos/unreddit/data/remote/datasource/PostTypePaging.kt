package com.cosmos.unreddit.data.remote.datasource

import com.cosmos.unreddit.data.model.PostTypeFilter
import com.cosmos.unreddit.data.remote.api.reddit.model.Child
import com.cosmos.unreddit.data.remote.api.reddit.model.PostChild

/**
 * A page of posts as a source loaded it, before it is handed to Paging.
 */
internal data class PostPage<Key : Any>(
    val posts: List<Child>,
    val nextKey: Key?,
    val prevKey: Key? = null
)

/**
 * The children of this page that [postTypeFilter] accepts.
 *
 * Children that are not posts are left in place: this narrows a page by post type only, and the
 * callers downstream already deal with whatever else a listing may hold.
 */
internal fun List<Child>.filterByPostType(postTypeFilter: PostTypeFilter): List<Child> {
    return when (postTypeFilter) {
        PostTypeFilter.ALL -> this
        else -> filter { it !is PostChild || postTypeFilter.matches(it.data.postType) }
    }
}

/**
 * Requests pages with [fetchPage], starting from [key], until one holds at least one post, the
 * listing runs out, or [MAX_REQUESTS_PER_LOAD] requests have been spent.
 *
 * A page left empty by [filterByPostType] does not merely waste a slot, it stalls paging. Paging
 * asks for more only once the presented list runs short of its prefetch distance, and it measures
 * that against the pages the source returned. A page that presents nothing yet is not the last one
 * leaves the list empty with nothing left to ask with: no items means no item access, and no item
 * access means no load hint. Skipping empty pages here keeps the fetcher moving.
 *
 * This is also why the post type filter belongs to the sources rather than to a `PagingData`
 * transform in the view models. Those run downstream of the fetcher, which would go on counting
 * the posts they dropped as presented.
 *
 * The cap bounds a single load, not the search: if every page within it was empty, Paging sees a
 * short page and appends again, so a filter that matches nothing for a long stretch still
 * advances — a few requests at a time rather than in one unbounded burst.
 */
internal suspend fun <Key : Any> loadUntilNotEmpty(
    key: Key?,
    hasMore: (Key?) -> Boolean = { it != null },
    fetchPage: suspend (Key?) -> PostPage<Key>
): PostPage<Key> {
    var page = fetchPage(key)
    var requests = 1

    while (page.posts.isEmpty() && hasMore(page.nextKey) && requests < MAX_REQUESTS_PER_LOAD) {
        page = fetchPage(page.nextKey)
        requests++
    }

    return page
}

/**
 * How many requests one load may spend looking for a page with a post the filter accepts.
 */
private const val MAX_REQUESTS_PER_LOAD = 3
