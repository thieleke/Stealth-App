package com.cosmos.unreddit.data.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PostTypeFilterTest {

    @Test
    fun `all matches every post type`() {
        PostTypeFilter.ALL.let { filter ->
            assertTrue(filter.matches(PostType.TEXT))
            assertTrue(filter.matches(PostType.LINK))
            assertTrue(filter.matches(PostType.IMAGE))
            assertTrue(filter.matches(PostType.VIDEO))
        }
    }

    @Test
    fun `text matches text and link posts only`() {
        PostTypeFilter.TEXT.let { filter ->
            assertTrue(filter.matches(PostType.TEXT))
            assertTrue(filter.matches(PostType.LINK))
            assertFalse(filter.matches(PostType.IMAGE))
            assertFalse(filter.matches(PostType.VIDEO))
        }
    }

    @Test
    fun `media matches image and video posts only`() {
        PostTypeFilter.MEDIA.let { filter ->
            assertFalse(filter.matches(PostType.TEXT))
            assertFalse(filter.matches(PostType.LINK))
            assertTrue(filter.matches(PostType.IMAGE))
            assertTrue(filter.matches(PostType.VIDEO))
        }
    }

    @Test
    fun `image matches image posts only`() {
        PostTypeFilter.IMAGE.let { filter ->
            assertFalse(filter.matches(PostType.TEXT))
            assertFalse(filter.matches(PostType.LINK))
            assertTrue(filter.matches(PostType.IMAGE))
            assertFalse(filter.matches(PostType.VIDEO))
        }
    }

    @Test
    fun `video matches video posts only`() {
        PostTypeFilter.VIDEO.let { filter ->
            assertFalse(filter.matches(PostType.TEXT))
            assertFalse(filter.matches(PostType.LINK))
            assertFalse(filter.matches(PostType.IMAGE))
            assertTrue(filter.matches(PostType.VIDEO))
        }
    }
}
