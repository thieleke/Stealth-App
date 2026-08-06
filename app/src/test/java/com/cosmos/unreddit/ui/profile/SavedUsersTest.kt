package com.cosmos.unreddit.ui.profile

import com.cosmos.unreddit.data.model.MediaType
import com.cosmos.unreddit.data.model.PostType
import com.cosmos.unreddit.data.model.PosterType
import com.cosmos.unreddit.data.model.SavedUsersRefresh
import com.cosmos.unreddit.data.model.Sort
import com.cosmos.unreddit.data.model.Sorting
import com.cosmos.unreddit.data.model.UserSortMode
import com.cosmos.unreddit.data.model.db.PostEntity
import com.cosmos.unreddit.data.model.preferences.ContentPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

class SavedUsersTest {

    @Test
    fun `deduplicates authors ignoring case and keeps the most recently saved post`() {
        val posts = listOf(
            post(id = "1", author = "Foo", time = 100),
            post(id = "2", author = "foo", time = 300),
            post(id = "3", author = "FOO", time = 200),
            post(id = "4", author = "bar", time = 50)
        )

        val users = getSavedUsers(posts, UserSortMode.CHRONOLOGICAL)

        assertEquals(2, users.size)
        // The kept post's own casing is what gets displayed
        assertEquals(listOf("2", "4"), users.map { it.id })
        assertEquals(listOf("foo", "bar"), users.map { it.author })
    }

    @Test
    fun `alphabetical mode sorts by username ignoring case`() {
        val posts = listOf(
            post(id = "1", author = "charlie", time = 300),
            post(id = "2", author = "Alice", time = 100),
            post(id = "3", author = "bob", time = 200)
        )

        val users = getSavedUsers(posts, UserSortMode.ALPHABETICAL)

        assertEquals(listOf("Alice", "bob", "charlie"), users.map { it.author })
    }

    @Test
    fun `chronological mode sorts by latest save time descending`() {
        val posts = listOf(
            post(id = "1", author = "alice", time = 100),
            post(id = "2", author = "bob", time = 300),
            post(id = "3", author = "charlie", time = 200)
        )

        val users = getSavedUsers(posts, UserSortMode.CHRONOLOGICAL)

        assertEquals(listOf("bob", "charlie", "alice"), users.map { it.author })
    }

    @Test
    fun `nsfw posts are excluded when showNsfw is off`() {
        val posts = listOf(
            post(id = "1", author = "alice", time = 100),
            post(id = "2", author = "alice", time = 300, isOver18 = true),
            post(id = "3", author = "bob", time = 200, isOver18 = true)
        )

        val users = getSavedUsers(posts, UserSortMode.ALPHABETICAL, showNsfw = false)

        // bob only has NSFW posts and is absent, alice falls back to her SFW post
        assertEquals(listOf("alice"), users.map { it.author })
        assertEquals(listOf("1"), users.map { it.id })

        val allUsers = getSavedUsers(posts, UserSortMode.ALPHABETICAL, showNsfw = true)

        assertEquals(listOf("alice", "bob"), allUsers.map { it.author })
        assertEquals(listOf("2", "3"), allUsers.map { it.id })
    }

    @Test
    fun `tie on save time falls back to creation time`() {
        val posts = listOf(
            post(id = "1", author = "alice", time = 100, created = 10),
            post(id = "2", author = "alice", time = 100, created = 30),
            post(id = "3", author = "alice", time = 100, created = 20)
        )

        val users = getSavedUsers(posts, UserSortMode.ALPHABETICAL)

        assertEquals(listOf("2"), users.map { it.id })
    }

    @Test
    fun `deleted author is a regular row`() {
        val posts = listOf(
            post(id = "1", author = "[deleted]", time = 100),
            post(id = "2", author = "[deleted]", time = 200),
            post(id = "3", author = "alice", time = 50)
        )

        val users = getSavedUsers(posts, UserSortMode.CHRONOLOGICAL)

        assertEquals(listOf("[deleted]", "alice"), users.map { it.author })
        assertEquals(listOf("2", "3"), users.map { it.id })
    }

    @Test
    fun `timeline sort orders by post creation, not save time`() {
        // Rows as they come back from the network: newest post wins regardless of when the
        // user's post was saved.
        val rows = listOf(
            post(id = "1", author = "alice", time = 900, created = 10),
            post(id = "2", author = "bob", time = 100, created = 30),
            post(id = "3", author = "charlie", time = 500, created = 20)
        )

        assertEquals(
            listOf("bob", "charlie", "alice"),
            ProfileViewModel.sortUsers(rows, UserSortMode.CHRONOLOGICAL).map { it.author }
        )
    }

    @Test
    fun `timeline alphabetical sort ignores case`() {
        val rows = listOf(
            post(id = "1", author = "charlie", time = 100, created = 30),
            post(id = "2", author = "Alice", time = 200, created = 10),
            post(id = "3", author = "bob", time = 300, created = 20)
        )

        assertEquals(
            listOf("Alice", "bob", "charlie"),
            ProfileViewModel.sortUsers(rows, UserSortMode.ALPHABETICAL).map { it.author }
        )
    }

    @Test
    fun `timeline shows the cached post and falls back to the saved one`() {
        val savedPerUser = listOf(
            post(id = "saved-alice", author = "Alice", time = 100),
            post(id = "saved-bob", author = "bob", time = 200)
        )
        // Only alice has been fetched, and under her lowercase key
        val latestPosts = mapOf("alice" to post(id = "latest-alice", author = "Alice", time = -1))

        val timeline = buildTimeline(savedPerUser, latestPosts)

        assertEquals(listOf("latest-alice", "saved-bob"), timeline.map { it.id })
    }

    @Test
    fun `cached nsfw post is skipped when showNsfw is turned off`() {
        val savedPerUser = listOf(post(id = "saved-alice", author = "alice", time = 100))
        val latestPosts = mapOf(
            "alice" to post(id = "latest-alice", author = "alice", time = -1, isOver18 = true)
        )

        assertEquals(
            listOf("latest-alice"),
            buildTimeline(savedPerUser, latestPosts, showNsfw = true).map { it.id }
        )
        assertEquals(
            listOf("saved-alice"),
            buildTimeline(savedPerUser, latestPosts, showNsfw = false).map { it.id }
        )
    }

    @Test
    fun `timeline flags the rows as seen and saved`() {
        val savedPerUser = listOf(
            post(id = "saved-alice", author = "alice", time = 100),
            post(id = "saved-bob", author = "bob", time = 200)
        )
        val latestPosts = mapOf("alice" to post(id = "latest-alice", author = "alice", time = -1))

        val timeline = buildTimeline(
            savedPerUser,
            latestPosts,
            history = setOf("latest-alice"),
            savedIds = setOf("saved-bob")
        )

        assertEquals(listOf(true, false), timeline.map { it.seen })
        assertEquals(listOf(false, true), timeline.map { it.saved })
    }

    @Test
    fun `a user that has never been fetched is always outdated`() {
        assertTrue(ProfileViewModel.isOutdated(null, NOW, EIGHT_HOURS))
        // Even with automatic refreshes off
        assertTrue(ProfileViewModel.isOutdated(null, NOW, 0))
    }

    @Test
    fun `a fetched user is outdated once the refresh period has passed`() {
        assertFalse(ProfileViewModel.isOutdated(NOW - EIGHT_HOURS + 1, NOW, EIGHT_HOURS))
        assertTrue(ProfileViewModel.isOutdated(NOW - EIGHT_HOURS, NOW, EIGHT_HOURS))

        // The same entry under a shorter period
        assertTrue(ProfileViewModel.isOutdated(NOW - EIGHT_HOURS + 1, NOW, TWO_HOURS))
    }

    @Test
    fun `a fetched user is never outdated when automatic refreshes are off`() {
        assertFalse(ProfileViewModel.isOutdated(0, NOW, 0))
    }

    @Test
    fun `refresh periods match their labels`() {
        assertEquals(0L, SavedUsersRefresh.NONE.period)
        assertEquals(TWO_HOURS, SavedUsersRefresh.TWO_HOURS.period)
        assertEquals(EIGHT_HOURS, SavedUsersRefresh.EIGHT_HOURS.period)

        assertEquals(SavedUsersRefresh.EIGHT_HOURS, SavedUsersRefresh.DEFAULT)
        // An unknown stored value falls back to the default rather than to no refresh at all
        assertEquals(SavedUsersRefresh.DEFAULT, SavedUsersRefresh.fromHours(3))
        assertEquals(SavedUsersRefresh.TWO_HOURS, SavedUsersRefresh.fromHours(2))
        assertEquals(SavedUsersRefresh.NONE, SavedUsersRefresh.fromIndex(0))
    }

    @Test
    fun `no saved posts yields no users`() {
        assertEquals(emptyList<PostEntity>(), getSavedUsers(emptyList(), UserSortMode.ALPHABETICAL))
    }

    private fun buildTimeline(
        savedPerUser: List<PostEntity>,
        latestPosts: Map<String, PostEntity>,
        showNsfw: Boolean = true,
        history: Set<String> = emptySet(),
        savedIds: Set<String> = emptySet()
    ): List<PostEntity> {
        return ProfileViewModel.buildTimeline(
            savedPerUser,
            latestPosts,
            contentPreferences(showNsfw),
            history,
            savedIds,
            UserSortMode.ALPHABETICAL
        )
    }

    private fun getSavedUsers(
        posts: List<PostEntity>,
        sortMode: UserSortMode,
        showNsfw: Boolean = true
    ): List<PostEntity> {
        return ProfileViewModel.getSavedUsers(posts, contentPreferences(showNsfw), sortMode)
    }

    private fun contentPreferences(showNsfw: Boolean): ContentPreferences {
        return ContentPreferences(
            showNsfw = showNsfw,
            showNsfwPreview = false,
            showSpoilerPreview = false,
            largePreview = false
        )
    }

    private fun post(
        id: String,
        author: String,
        time: Long,
        created: Long = 0,
        isOver18: Boolean = false
    ): PostEntity {
        return PostEntity(
            id = id,
            subreddit = "subreddit",
            title = "title",
            ratio = 0,
            totalAwards = 0,
            isOC = false,
            score = "0",
            type = PostType.TEXT,
            domain = "domain",
            isSelf = true,
            selfTextHtml = null,
            suggestedSorting = Sorting(Sort.BEST),
            isOver18 = isOver18,
            preview = null,
            isSpoiler = false,
            isArchived = false,
            isLocked = false,
            posterType = PosterType.REGULAR,
            author = author,
            commentsNumber = "0",
            permalink = "permalink",
            isStickied = false,
            url = "url",
            created = created,
            mediaType = MediaType.NO_MEDIA,
            mediaUrl = "",
            time = time
        )
    }

    companion object {
        private val TWO_HOURS = TimeUnit.HOURS.toMillis(2)
        private val EIGHT_HOURS = TimeUnit.HOURS.toMillis(8)

        private val NOW = TimeUnit.DAYS.toMillis(20000)
    }
}
