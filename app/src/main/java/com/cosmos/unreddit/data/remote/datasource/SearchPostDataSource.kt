package com.cosmos.unreddit.data.remote.datasource

import com.cosmos.unreddit.data.model.PostTypeFilter
import com.cosmos.unreddit.data.model.Sorting
import com.cosmos.unreddit.data.remote.api.reddit.model.Listing
import com.cosmos.unreddit.data.remote.api.reddit.source.CurrentSource

class SearchPostDataSource(
    private val source: CurrentSource,
    query: String,
    sorting: Sorting,
    postTypeFilter: PostTypeFilter = PostTypeFilter.ALL
) : PostListDataSource(source, query, sorting, postTypeFilter) {

    override suspend fun getResponse(query: String, sorting: Sorting, after: String?): Listing {
        return source.searchPost(query, sorting.generalSorting, sorting.timeSorting, after)
    }
}
