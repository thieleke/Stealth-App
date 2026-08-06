package com.cosmos.unreddit.ui.profile

import android.os.Parcelable
import androidx.lifecycle.viewModelScope
import com.cosmos.unreddit.data.local.mapper.PostMapper2
import com.cosmos.unreddit.data.local.mapper.SavedMapper2
import com.cosmos.unreddit.data.model.Comment
import com.cosmos.unreddit.data.model.SavedItem
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
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
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

    private val userFlags: Flow<Pair<List<String>, List<String>>> = combine(
        historyIds,
        savedPostIds
    ) { history, saved -> history to saved }

    private val usersRefresh: MutableStateFlow<Int> = MutableStateFlow(0)

    /**
     * Latest post fetched per user, keyed by lowercase username. Keeps the timeline from
     * re-hitting the network every time the saved posts or preferences change; cleared on refresh.
     */
    private val latestPostCache = ConcurrentHashMap<String, PostEntity>()

    private val _usersLoading: MutableStateFlow<Boolean> = MutableStateFlow(false)
    val usersLoading: StateFlow<Boolean> = _usersLoading

    /**
     * One row per unique author of the saved posts, each showing that user's *current* newest
     * submission fetched from the network — a live timeline rather than the stored saved post.
     * Falls back to the saved post when the fetch fails, so a row never disappears offline.
     */
    val savedUsers: Flow<List<PostEntity>> = combine(
        _savedPosts,
        contentPreferences,
        userSortMode,
        userFlags,
        usersRefresh
    ) { posts, preferences, sortMode, flags, _ ->
        UsersInput(posts, preferences, sortMode, flags.first, flags.second)
    }.mapLatest { input ->
        val savedPerUser = getSavedUsers(input.posts, input.preferences, input.sortMode)

        if (savedPerUser.isEmpty()) {
            return@mapLatest emptyList()
        }

        _usersLoading.value = true
        try {
            sortUsers(fetchLatestPosts(savedPerUser, input), input.sortMode)
        } finally {
            _usersLoading.value = false
        }
    }.flowOn(defaultDispatcher)

    private suspend fun fetchLatestPosts(
        savedPerUser: List<PostEntity>,
        input: UsersInput
    ): List<PostEntity> = coroutineScope {
        val semaphore = Semaphore(MAX_PARALLEL_USER_REQUESTS)

        savedPerUser.map { savedPost ->
            async {
                val key = savedPost.author.lowercase()

                val latest = latestPostCache[key] ?: runCatching {
                    semaphore.withPermit {
                        postMapper
                            .dataToEntities(repository.getUserLatestPosts(savedPost.author))
                            .firstOrNull { input.preferences.showNsfw || !it.isOver18 }
                    }
                }.getOrNull()?.also { latestPostCache[key] = it }

                (latest ?: savedPost).apply {
                    seen = input.history.contains(id)
                    saved = input.savedIds.contains(id)
                }
            }
        }.awaitAll()
    }

    fun refreshUsers() {
        latestPostCache.clear()
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

    private data class UsersInput(
        val posts: List<PostEntity>,
        val preferences: ContentPreferences,
        val sortMode: UserSortMode,
        val history: List<String>,
        val savedIds: List<String>
    )

    companion object {
        private const val LAST_TAB_INDEX = 1

        private const val MAX_PARALLEL_USER_REQUESTS = 4

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
