package com.cosmos.unreddit.data.remote.datasource

import com.cosmos.unreddit.data.model.PostTypeFilter
import com.cosmos.unreddit.data.remote.api.reddit.model.Child
import com.cosmos.unreddit.data.remote.api.reddit.model.MoreChild
import com.cosmos.unreddit.data.remote.api.reddit.model.MoreData
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class PostTypePagingTest {

    @Test
    fun `a page that already has posts costs a single request`() = runBlocking {
        val requested = mutableListOf<String?>()

        val page = loadUntilNotEmpty<String>("first") { key ->
            requested += key
            PostPage(children(1), "next")
        }

        assertEquals(listOf<String?>("first"), requested)
        assertEquals(1, page.posts.size)
        assertEquals("next", page.nextKey)
    }

    @Test
    fun `empty pages are skipped until one holds a post`() = runBlocking {
        val requested = mutableListOf<String?>()
        val pages = mapOf(
            null to PostPage(emptyList<Child>(), "b"),
            "b" to PostPage(children(2), "c")
        )

        val page = loadUntilNotEmpty<String>(null) { key ->
            requested += key
            pages.getValue(key)
        }

        assertEquals(listOf(null, "b"), requested)
        assertEquals(2, page.posts.size)
        assertEquals("c", page.nextKey)
    }

    @Test
    fun `the end of the listing stops the search`() = runBlocking {
        var requests = 0

        val page = loadUntilNotEmpty<String>(null) {
            requests++
            PostPage(emptyList(), null)
        }

        assertEquals(1, requests)
        assertTrue(page.posts.isEmpty())
        assertNull(page.nextKey)
    }

    @Test
    fun `one load cannot page through the whole listing`() = runBlocking {
        var requests = 0

        val page = loadUntilNotEmpty<String>(null) {
            requests++
            PostPage(emptyList(), "more")
        }

        // Bounded, but the last key is kept so Paging can append again from where this stopped.
        assertEquals(3, requests)
        assertEquals("more", page.nextKey)
    }

    @Test
    fun `a key is spent only once every chunk of it is`() = runBlocking {
        val requested = mutableListOf<List<String>?>()
        val hasMore: (List<String>?) -> Boolean = { key -> key?.any { it.isNotBlank() } == true }
        val pages = mapOf(
            listOf("a", "b") to PostPage(emptyList<Child>(), listOf("", "b2")),
            listOf("", "b2") to PostPage(emptyList(), listOf("", ""))
        )

        val page = loadUntilNotEmpty(listOf("a", "b"), hasMore) { key ->
            requested += key
            pages.getValue(key!!)
        }

        // The first chunk ran out, the second had not, so the second page was still worth asking
        // for. Once every chunk is spent the search stops rather than re-reading the listing.
        assertEquals(listOf(listOf("a", "b"), listOf("", "b2")), requested)
        assertEquals(listOf("", ""), page.nextKey)
    }

    @Test
    fun `ALL passes the page through untouched`() {
        val page = children(3)

        assertSame(page, page.filterByPostType(PostTypeFilter.ALL))
    }

    @Test
    fun `children that are not posts survive filtering`() {
        val page = children(2)

        assertEquals(page, page.filterByPostType(PostTypeFilter.VIDEO))
    }

    private fun children(count: Int): List<Child> = List(count) {
        MoreChild(MoreData(0, "t3_$it", "$it", null, "t3_parent", mutableListOf()))
    }
}
