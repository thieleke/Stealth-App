package com.cosmos.unreddit.ui.profile

import android.os.Parcelable
import androidx.lifecycle.viewModelScope
import com.cosmos.unreddit.data.local.mapper.PostMapper2
import com.cosmos.unreddit.data.local.mapper.SavedMapper2
import com.cosmos.unreddit.data.model.Comment
import com.cosmos.unreddit.data.model.SavedItem
import com.cosmos.unreddit.data.model.SavedUsersRefresh
import com.cosmos.unreddit.data.model.UserSortMode
import com.cosmos.unreddit.data.model.db.PostEntity
import com.cosmos.unreddit.data.model.db.Profile
import com.cosmos.unreddit.data.model.preferences.ContentPreferences
import com.cosmos.unreddit.data.repository.PostListRepository
import com.cosmos.unreddit.data.repository.PreferencesRepository
import com.cosmos.unreddit.di.DispatchersModule.DefaultDispatcher
import com.cosmos.unreddit.ui.base.BaseViewModel
import com.cosmos.unreddit.util.extension.updateValue
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.combineTransform
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject

@HiltViewModel
class ProfileViewModel @Inject constructor(
    private val preferencesRepository: PreferencesRepository,
    private val repository: PostListRepository,
    private val savedMapper: SavedMapper2,
    private val postMapper: PostMapper2,
    @DefaultDispatcher private val defaultDispatcher: CoroutineDispatcher
) : BaseViewModel(preferencesRepository, repository) {

    val contentPreferences: Flow<ContentPreferences> = preferencesRepository.getContentPreferences()

    private val _page: MutableStateFlow<Int> = MutableStateFlow(0)
    val page: StateFlow<Int> get() = _page

    var layoutState: Int? = null

    /**
     * Scroll state of the list of each tab, kept while the fragment views are destroyed, e.g. when
     * navigating to a user or a subreddit
     */
    var savedListState: Parcelable? = null
    var usersListState: Parcelable? = null

    private val _savedPosts: Flow<List<PostEntity>> = currentProfile.flatMapLatest {
        repository.getSavedPosts(it.id)
    }

    private val _savedComments: Flow<List<Comment.CommentEntity>> = currentProfile.flatMapLatest {
        repository.getSavedComments(it.id)
    }

    val selectedProfile: Flow<Profile> = combine(
        currentProfile,
        repository.getAllProfiles()
    ) { currentProfile, profiles ->
        // Update current profile when any profile is updated
        profiles.find { it.id == currentProfile.id } ?: currentProfile
    }

    val savedItems: Flow<List<SavedItem>> = combineTransform(
        _savedPosts,
        _savedComments,
        contentPreferences
    ) { _posts, _comments, preferences ->
        coroutineScope {
            val posts = async {
                savedMapper.postsToEntities(_posts).filter {
                    preferences.showNsfw || !(it as SavedItem.Post).post.isOver18
                }
            }

            val comments = async {
                savedMapper.commentsToEntities(_comments)
            }

            val items = mutableListOf<SavedItem>().apply {
                addAll(posts.await())
                addAll(comments.await())
            }

            emit(items)
        }
    }.map { items ->
        items.sortedByDescending { it.timestamp }
    }.flowOn(defaultDispatcher)

    /**
     * One-shot initial tab for [ProfileFragment]. Read with `first()`, never collected
     * continuously: a late emission would otherwise fight the user's own tab swipes.
     */
    val savedLastTab: Flow<Int> = preferencesRepository.getSavedLastTab()
        .map { it.coerceIn(0, LAST_TAB_INDEX) }

    // DataStore is the single source of truth for the sort mode
    val userSortMode: StateFlow<UserSortMode> = preferencesRepository.getSavedUsersSortMode()
        .map { UserSortMode.fromValue(it) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, UserSortMode.ALPHABETICAL)

    private val userFlags: Flow<UserFlags> = combine(
        currentProfile,
        historyIds,
        savedPostIds
    ) { profile, history, saved -> UserFlags(profile.id, history.toSet(), saved.toSet()) }

    private val usersRefresh: MutableStateFlow<Int> = MutableStateFlow(0)

    /**
     * Refresh requests, explicit ones through [usersRefresh] and the configured period. Shortening
     * the period refetches what it makes outdated straight away.
     */
    private val usersRefreshTrigger: Flow<Long> = combine(
        usersRefresh,
        preferencesRepository.getSavedUsersRefresh()
    ) { _, hours -> SavedUsersRefresh.fromHours(hours).period }

    /**
     * Set by [refreshUsers] and consumed once a fetch pass completes, so an interrupted refresh
     * is picked up again by the pass that replaces it.
     */
    @Volatile
    private var forceUsersRefresh: Boolean = false

    /**
     * When the last fetch of a user failed, keyed by lowercase username.
     *
     * A user whose fetch fails — deleted, suspended, renamed, or simply unreachable — never makes
     * it into the database cache, so [isOutdated] would report them as outdated forever and every
     * single pass would go back to the network for them. That is one wasted request per failing
     * user per pass, which is what hurts once a profile has a lot of saved users.
     *
     * Deliberately in memory rather than in the cache table: a failure must not be allowed to
     * freeze a user on their saved post across restarts, and it is worth retrying a user whose
     * fetch failed because the network was down. Cleared by [refreshUsers], so a pull to refresh
     * always retries everyone.
     */
    private val failedUserFetches = ConcurrentHashMap<String, Long>()

    private val _usersLoading: MutableStateFlow<Boolean> = MutableStateFlow(false)
    val usersLoading: StateFlow<Boolean> = _usersLoading

    /**
     * When the timeline rows were last fetched, or 0 when nothing is cached yet. Reflects the
     * cache rather than the time of the last screen visit, which would otherwise claim a
     * hours-old timeline had just been refreshed.
     */
    private val _usersLastRefresh: MutableStateFlow<Long> = MutableStateFlow(0)
    val usersLastRefresh: StateFlow<Long> = _usersLastRefresh

    /**
     * One row per unique author of the saved posts, each showing that user's *current* newest
     * submission — a live timeline rather than the stored saved post.
     *
     * Fetching that post is a request per user, so the results are cached in the database and
     * only refetched when [refreshUsers] is called or the entry is older than the configured
     * [SavedUsersRefresh] period. Emits twice when a fetch is needed: first the cached timeline,
     * so the panel is usable straight away, then the updated one.
     *
     * Falls back to the saved post for users that have never been fetched successfully, so a row
     * never disappears offline.
     */
    val savedUsers: Flow<List<PostEntity>> = combine(
        _savedPosts,
        contentPreferences,
        userSortMode,
        userFlags,
        usersRefreshTrigger
    ) { posts, preferences, sortMode, flags, refreshPeriod ->
        UsersInput(posts, preferences, sortMode, flags, refreshPeriod)
    }.transformLatest { input ->
        val savedPerUser = getSavedUsers(input.posts, input.preferences, input.sortMode)

        if (savedPerUser.isEmpty()) {
            forceUsersRefresh = false
            _usersLastRefresh.value = 0
            emit(emptyList())
            return@transformLatest
        }

        val (_, history, savedIds) = input.flags
        val cache = repository.getSavedUserPosts(input.profileId).associateBy { it.authorKey }
        val latestPosts = cache.mapValues { (_, entry) -> entry.post }

        _usersLastRefresh.value = cache.values.maxOfOrNull { it.fetchedAt } ?: 0
        emit(
            buildTimeline(
                savedPerUser, latestPosts, input.preferences, history, savedIds, input.sortMode
            )
        )

        val now = System.currentTimeMillis()
        val forced = forceUsersRefresh
        val outdated = savedPerUser.filter { savedPost ->
            val key = savedPost.authorKey
            when {
                forced -> true
                // A user whose fetch just failed is left alone until the period is up, rather
                // than retried on every pass
                !isOutdated(failedUserFetches[key], now, input.refreshPeriod) -> false
                else -> isOutdated(cache[key]?.fetchedAt, now, input.refreshPeriod)
            }
        }

        // Users whose posts are no longer saved: drop what is cached for them. Computed from the
        // cache rather than passing every current author to the query, which would bind one
        // variable per saved user and blow SQLite's limit on a large profile
        repository.pruneSavedUserPosts(
            input.profileId,
            cache.keys - savedPerUser.mapTo(mutableSetOf()) { it.authorKey }
        )
        failedUserFetches.keys.retainAll(savedPerUser.mapTo(mutableSetOf()) { it.authorKey })

        if (outdated.isEmpty()) {
            return@transformLatest
        }

        _usersLoading.value = true
        try {
            val fetched = fetchLatestPosts(outdated, input, now)
            forceUsersRefresh = false

            if (fetched.isNotEmpty()) {
                _usersLastRefresh.value = now
                emit(
                    buildTimeline(
                        savedPerUser,
                        latestPosts + fetched,
                        input.preferences,
                        history,
                        savedIds,
                        input.sortMode
                    )
                )
            }
        } finally {
            _usersLoading.value = false
        }
    }.flowOn(defaultDispatcher)

    /**
     * Fetches the newest post of each of [users] and caches it, keyed by lowercase username.
     *
     * A user can fail for reasons that are not going to sort themselves out — deleted, suspended,
     * renamed — as well as for a network blip, and neither must take the whole pass down with it.
     * Those users are left out of the result, so they keep showing their saved post, and are
     * remembered in [failedUserFetches] so the next pass does not immediately try them again.
     */
    private suspend fun fetchLatestPosts(
        users: List<PostEntity>,
        input: UsersInput,
        fetchedAt: Long
    ): Map<String, PostEntity> = coroutineScope {
        val semaphore = Semaphore(MAX_PARALLEL_USER_REQUESTS)

        users.map { savedPost ->
            async {
                val key = savedPost.authorKey

                val latest = try {
                    semaphore.withPermit {
                        // Filter on the raw data and map only the kept post: mapping parses the
                        // selftext HTML, too costly for posts that are thrown away
                        repository.getUserLatestPosts(savedPost.author)
                            .firstOrNull { input.preferences.showNsfw || !it.isOver18 }
                            ?.let { postMapper.dataToEntity(it) }
                    }
                } catch (e: CancellationException) {
                    // The pass is being replaced by a newer one; not a failure of this user
                    throw e
                } catch (e: Exception) {
                    null
                }

                if (latest == null) {
                    // Also covers a user whose account is there but has no post to show
                    failedUserFetches[key] = fetchedAt
                    null
                } else {
                    failedUserFetches.remove(key)
                    repository.cacheSavedUserPost(key, latest, input.profileId, fetchedAt)
                    key to latest
                }
            }
        }.awaitAll().filterNotNull().toMap()
    }

    fun refreshUsers() {
        forceUsersRefresh = true
        // An explicit refresh retries the users that failed, however recently
        failedUserFetches.clear()
        usersRefresh.value++
    }

    fun setPage(position: Int) {
        _page.updateValue(position)
        viewModelScope.launch {
            preferencesRepository.setSavedLastTab(position)
        }
    }

    fun setUserSortMode(sortMode: UserSortMode) {
        viewModelScope.launch {
            preferencesRepository.setSavedUsersSortMode(sortMode.value)
        }
    }

    private data class UserFlags(
        val profileId: Int,
        val history: Set<String>,
        val savedIds: Set<String>
    )

    private data class UsersInput(
        val posts: List<PostEntity>,
        val preferences: ContentPreferences,
        val sortMode: UserSortMode,
        val flags: UserFlags,
        val refreshPeriod: Long
    ) {
        val profileId: Int get() = flags.profileId
    }

    companion object {
        private const val LAST_TAB_INDEX = 1

        private const val MAX_PARALLEL_USER_REQUESTS = 4

        /** Lowercase author, the key both the cache and the timeline are built on */
        private val PostEntity.authorKey: String get() = author.lowercase()

        /**
         * Whether a user has to be fetched again, [fetchedAt] being null for one that never was.
         * A [refreshPeriod] of 0, i.e. [SavedUsersRefresh.NONE], only fetches those.
         */
        internal fun isOutdated(fetchedAt: Long?, now: Long, refreshPeriod: Long): Boolean {
            return when {
                fetchedAt == null -> true
                refreshPeriod <= 0 -> false
                else -> now - fetchedAt >= refreshPeriod
            }
        }

        /**
         * The rows to display: the latest post known for each of [savedPerUser], falling back to
         * the saved post itself for the users [latestPosts] has nothing usable for.
         *
         * A cached post is skipped when NSFW content is hidden but the post is over 18, which
         * happens when the preference is turned off after the post was fetched. The row shows the
         * saved post until the entry is refreshed.
         */
        internal fun buildTimeline(
            savedPerUser: List<PostEntity>,
            latestPosts: Map<String, PostEntity>,
            preferences: ContentPreferences,
            history: Set<String>,
            savedIds: Set<String>,
            sortMode: UserSortMode
        ): List<PostEntity> {
            val users = savedPerUser.map { savedPost ->
                val latest = latestPosts[savedPost.authorKey]
                    ?.takeIf { preferences.showNsfw || !it.isOver18 }

                (latest ?: savedPost).apply {
                    seen = history.contains(id)
                    saved = savedIds.contains(id)
                }
            }

            return sortUsers(users, sortMode)
        }

        /**
         * Ordering of the timeline rows. Applied *after* the latest posts are fetched, so
         * [UserSortMode.CHRONOLOGICAL] reflects the current posts rather than when they were saved.
         */
        internal fun sortUsers(
            users: List<PostEntity>,
            sortMode: UserSortMode
        ): List<PostEntity> {
            return when (sortMode) {
                UserSortMode.ALPHABETICAL -> users.sortedBy { it.author.lowercase() }
                UserSortMode.CHRONOLOGICAL -> users.sortedByDescending { it.created }
            }
        }

        /**
         * One post per unique author: the most recently saved one, sorted per [sortMode].
         */
        internal fun getSavedUsers(
            posts: List<PostEntity>,
            preferences: ContentPreferences,
            sortMode: UserSortMode
        ): List<PostEntity> {
            val latestByUser = posts
                .filter { preferences.showNsfw || !it.isOver18 }
                // Reddit usernames are case-insensitive
                .groupBy { it.author.lowercase() }
                .map { (_, userPosts) ->
                    userPosts.maxWithOrNull(compareBy({ it.time }, { it.created }))!!
                }

            return when (sortMode) {
                UserSortMode.ALPHABETICAL -> latestByUser.sortedBy { it.author.lowercase() }
                UserSortMode.CHRONOLOGICAL -> latestByUser.sortedByDescending { it.time }
            }
        }
    }
}
