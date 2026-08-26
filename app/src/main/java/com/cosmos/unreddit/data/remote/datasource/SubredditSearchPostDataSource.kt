package com.cosmos.unreddit.data.remote.datasource

import android.util.Log
import androidx.paging.PagingSource
import androidx.paging.PagingState
import com.cosmos.unreddit.data.model.PostTypeFilter
import com.cosmos.unreddit.data.model.Sorting
import com.cosmos.unreddit.data.remote.api.reddit.model.Child
import com.cosmos.unreddit.data.remote.api.reddit.source.CurrentSource

class SubredditSearchPostDataSource(
    private val source: CurrentSource,
    private val subreddit: String,
    private val query: String,
    private val sorting: Sorting,
    private val postTypeFilter: PostTypeFilter = PostTypeFilter.ALL
) : PagingSource<String, Child>() {

    override val keyReuseSupported: Boolean = true

    override suspend fun load(params: LoadParams<String>): LoadResult<String, Child> {
        return try {
            val page = loadUntilNotEmpty(params.key) { after ->
                val data = source.searchInSubreddit(
                    subreddit,
                    query,
                    sorting.generalSorting,
                    sorting.timeSorting,
                    after
                ).data

                PostPage(data.children.filterByPostType(postTypeFilter), data.after, data.before)
            }

            LoadResult.Page(page.posts, page.prevKey, page.nextKey)
        } catch (e: Exception) {
            Log.e("SubredditSearchSource", "Error", e)
            LoadResult.Error(e)
        }
    }

    override fun getRefreshKey(state: PagingState<String, Child>): String? {
        return state.anchorPosition?.let { anchorPosition ->
            state.closestPageToPosition(anchorPosition)?.prevKey
        }
    }
}
