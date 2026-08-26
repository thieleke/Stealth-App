package com.cosmos.unreddit.data.repository

import com.cosmos.unreddit.data.model.PostTypeFilter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Holds the current post type filter for the whole application session.
 *
 * The value is intentionally kept in memory only: it is shared across the main, subreddit and
 * user profile views (a Hilt singleton outlives every fragment and view model), but it is never
 * persisted, so it resets to [PostTypeFilter.ALL] when the application is exited.
 */
@Singleton
class PostTypeFilterRepository @Inject constructor() {

    private val _postTypeFilter = MutableStateFlow(PostTypeFilter.ALL)
    val postTypeFilter: StateFlow<PostTypeFilter> = _postTypeFilter.asStateFlow()

    fun setPostTypeFilter(filter: PostTypeFilter) {
        _postTypeFilter.value = filter
    }
}
