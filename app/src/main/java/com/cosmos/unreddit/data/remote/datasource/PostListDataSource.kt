package com.cosmos.unreddit.data.remote.datasource

import android.util.Log
import androidx.paging.PagingSource
import androidx.paging.PagingState
import com.cosmos.unreddit.data.model.PostTypeFilter
import com.cosmos.unreddit.data.model.Sorting
import com.cosmos.unreddit.data.remote.api.reddit.model.Child
import com.cosmos.unreddit.data.remote.api.reddit.model.Listing
import com.cosmos.unreddit.data.remote.api.reddit.source.CurrentSource
import com.squareup.moshi.JsonDataException
import retrofit2.HttpException
import java.io.IOException

open class PostListDataSource(
    private val source: CurrentSource,
    private val query: String,
    private val sorting: Sorting,
    private val postTypeFilter: PostTypeFilter = PostTypeFilter.ALL
) : PagingSource<String, Child>() {

    override val keyReuseSupported: Boolean = true

    override suspend fun load(params: LoadParams<String>): LoadResult<String, Child> {
        return try {
            val page = loadUntilNotEmpty(params.key) { after ->
                val data = getResponse(query, sorting, after).data

                PostPage(data.children.filterByPostType(postTypeFilter), data.after)
            }

            LoadResult.Page(page.posts, null, page.nextKey)
        } catch (exception: IOException) {
            Log.e("PostListDataSource", "Error", exception)
            LoadResult.Error(exception)
        } catch (exception: HttpException) {
            Log.e("PostListDataSource", "Error", exception)
            LoadResult.Error(exception)
        } catch (exception: JsonDataException) {
            LoadResult.Error(exception)
        }
    }

    open suspend fun getResponse(query: String, sorting: Sorting, after: String?): Listing {
        return source.getSubreddit(query, sorting.generalSorting, sorting.timeSorting, after)
    }

    override fun getRefreshKey(state: PagingState<String, Child>): String? {
        return state.anchorPosition?.let { anchorPosition ->
            state.closestPageToPosition(anchorPosition)?.prevKey
        }
    }
}
