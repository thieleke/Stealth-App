package com.cosmos.unreddit.data.repository

import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import com.cosmos.unreddit.data.local.RedditDatabase
import com.cosmos.unreddit.data.model.Comment
import com.cosmos.unreddit.data.model.PostTypeFilter
import com.cosmos.unreddit.data.model.Sort
import com.cosmos.unreddit.data.model.Sorting
import com.cosmos.unreddit.data.model.db.History
import com.cosmos.unreddit.data.model.db.PostEntity
import com.cosmos.unreddit.data.model.db.Profile
import com.cosmos.unreddit.data.model.db.SavedUserPost
import com.cosmos.unreddit.data.model.db.Subscription
import com.cosmos.unreddit.data.remote.api.reddit.model.AboutChild
import com.cosmos.unreddit.data.remote.api.reddit.model.AboutUserChild
import com.cosmos.unreddit.data.remote.api.reddit.model.Child
import com.cosmos.unreddit.data.remote.api.reddit.model.Listing
import com.cosmos.unreddit.data.remote.api.reddit.model.MoreChildren
import com.cosmos.unreddit.data.remote.api.reddit.model.PostChild
import com.cosmos.unreddit.data.remote.api.reddit.RedditRateLimiter
import com.cosmos.unreddit.data.remote.api.reddit.model.PostData
import com.cosmos.unreddit.data.remote.api.reddit.source.CurrentSource
import com.cosmos.unreddit.data.remote.datasource.CommentsDataSource
import com.cosmos.unreddit.data.remote.datasource.SearchPostDataSource
import com.cosmos.unreddit.data.remote.datasource.SearchSubredditDataSource
import com.cosmos.unreddit.data.remote.datasource.SearchUserDataSource
import com.cosmos.unreddit.data.remote.datasource.SmartPostListDataSource
import com.cosmos.unreddit.data.remote.datasource.SubredditSearchPostDataSource
import com.cosmos.unreddit.data.remote.datasource.UserPostsDataSource
import com.cosmos.unreddit.di.DispatchersModule.DefaultDispatcher
import com.cosmos.unreddit.di.DispatchersModule.MainImmediateDispatcher
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PostListRepository @Inject constructor(
    private val source: CurrentSource,
    private val redditDatabase: RedditDatabase,
    private val rateLimiter: RedditRateLimiter,
    @DefaultDispatcher private val defaultDispatcher: CoroutineDispatcher,
    @MainImmediateDispatcher private val mainImmediateDispatcher: CoroutineDispatcher
) {

    fun getPost(permalink: String, sorting: Sorting): Flow<List<Listing>> = flow {
        emit(source.getPost(permalink, sort = sorting.generalSorting))
    }

    fun getMoreChildren(children: String, linkId: String): Flow<MoreChildren> = flow {
        emit(source.getMoreChildren(children, linkId))
    }

    //region Subreddit

    fun getPosts(
        subreddit: String,
        sorting: Sorting,
        postTypeFilter: PostTypeFilter = PostTypeFilter.ALL,
        pageSize: Int = DEFAULT_LIMIT
    ): Flow<PagingData<Child>> {
        return Pager(PagingConfig(pageSize = pageSize)) {
            SmartPostListDataSource(
                source,
                listOf(subreddit),
                sorting,
                defaultDispatcher,
                mainImmediateDispatcher,
                postTypeFilter
            )
        }.flow
    }

    fun getPosts(
        subreddit: List<String>,
        sorting: Sorting,
        postTypeFilter: PostTypeFilter = PostTypeFilter.ALL,
        pageSize: Int = DEFAULT_LIMIT
    ): Flow<PagingData<Child>> {
        return Pager(PagingConfig(pageSize = pageSize)) {
            SmartPostListDataSource(
                source,
                subreddit,
                sorting,
                defaultDispatcher,
                mainImmediateDispatcher,
                postTypeFilter
            )
        }.flow
    }

    fun getSubredditInfo(subreddit: String): Flow<AboutChild> = flow {
        emit(source.getSubredditInfo(subreddit) as AboutChild)
    }

    //endregion

    //region Subscriptions

    fun getSubscriptions(profileId: Int): Flow<List<Subscription>> = redditDatabase
        .subscriptionDao().getSubscriptionsFromProfile(profileId).distinctUntilChanged()

    fun getSubscriptionsNames(profileId: Int): Flow<List<String>> {
        return redditDatabase.subscriptionDao().getSubscriptionsNamesFromProfile(profileId)
    }

    suspend fun subscribe(name: String, profileId: Int, icon: String? = null) {
        redditDatabase.subscriptionDao().insert(
            Subscription(name, System.currentTimeMillis(), icon, profileId)
        )
    }

    suspend fun unsubscribe(name: String, profileId: Int) {
        redditDatabase.subscriptionDao().deleteFromNameAndProfile(name, profileId)
    }

    //endregion

    //region User

    fun getUserPosts(
        user: String,
        sorting: Sorting,
        postTypeFilter: PostTypeFilter = PostTypeFilter.ALL,
        pageSize: Int = DEFAULT_LIMIT
    ): Flow<PagingData<Child>> {
        return Pager(PagingConfig(pageSize = pageSize)) {
            UserPostsDataSource(source, user, sorting, postTypeFilter)
        }.flow
    }

    fun getUserComments(
        user: String,
        sorting: Sorting,
        pageSize: Int = DEFAULT_LIMIT
    ): Flow<PagingData<Child>> {
        return Pager(PagingConfig(pageSize = pageSize)) {
            CommentsDataSource(source, user, sorting)
        }.flow
    }

    fun getUserInfo(user: String): Flow<AboutUserChild> = flow {
        emit(source.getUserInfo(user) as AboutUserChild)
    }

    /**
     * One-shot fetch of a user's newest submissions, most recent first. Used by the saved Users
     * timeline, which shows a single current post per user rather than a paged list.
     *
     * [Sort.NEW] is only a request. The official JSON source honors it, but the scraping and
     * Teddit sources return the page as their own backend sorted it, which for a user page is the
     * default (hot) listing — whose first entry is a popular post rather than the latest one. The
     * order is therefore imposed here instead of being taken on trust.
     *
     * For the same reason [limit] is applied *after* sorting: it is passed to the API to keep the
     * response small, but a source that cannot honor it returns its full page, and truncating
     * that page first would throw away the newest post before it was ever considered.
     */
    suspend fun getUserLatestPosts(user: String, limit: Int = USER_LATEST_LIMIT): List<PostData> {
        return source.getUserPosts(user, Sort.NEW, null, null, limit)
            .data
            .children
            .filterIsInstance<PostChild>()
            .map { it.data }
            .sortedByDescending { it.created }
            .take(limit)
    }

    //endregion

    //region Search

    fun searchPost(
        query: String,
        sorting: Sorting,
        postTypeFilter: PostTypeFilter = PostTypeFilter.ALL,
        pageSize: Int = DEFAULT_LIMIT
    ): Flow<PagingData<Child>> {
        return Pager(PagingConfig(pageSize = pageSize)) {
            SearchPostDataSource(source, query, sorting, postTypeFilter)
        }.flow
    }

    fun searchUser(
        query: String,
        sorting: Sorting,
        pageSize: Int = DEFAULT_LIMIT
    ): Flow<PagingData<Child>> {
        return Pager(PagingConfig(pageSize = pageSize)) {
            SearchUserDataSource(source, query, sorting)
        }.flow
    }

    fun searchSubreddit(
        query: String,
        sorting: Sorting,
        pageSize: Int = DEFAULT_LIMIT
    ): Flow<PagingData<Child>> {
        return Pager(PagingConfig(pageSize = pageSize)) {
            SearchSubredditDataSource(source, query, sorting)
        }.flow
    }

    fun searchInSubreddit(
        query: String,
        subreddit: String,
        sorting: Sorting,
        postTypeFilter: PostTypeFilter = PostTypeFilter.ALL,
        pageSize: Int = DEFAULT_LIMIT
    ): Flow<PagingData<Child>> {
        return Pager(PagingConfig(pageSize = pageSize)) {
            SubredditSearchPostDataSource(source, subreddit, query, sorting, postTypeFilter)
        }.flow
    }

    //endregion

    //region History

    fun getHistoryIds(profileId: Int): Flow<List<String>> {
        return redditDatabase.historyDao().getHistoryIdsFromProfile(profileId)
    }

    suspend fun insertPostInHistory(postId: String, profileId: Int) {
        redditDatabase.historyDao().upsert(History(postId, System.currentTimeMillis(), profileId))
    }

    //endregion

    //region Profile

    suspend fun addProfile(name: String) {
        redditDatabase.profileDao().insert(Profile(name = name))
    }

    suspend fun getProfile(id: Int): Profile {
        return redditDatabase.profileDao().getProfileFromId(id)
            ?: redditDatabase.profileDao().getFirstProfile()
    }

    fun getAllProfiles(): Flow<List<Profile>> {
        return redditDatabase.profileDao().getAllProfiles()
    }

    suspend fun deleteProfile(profileId: Int) {
        redditDatabase.profileDao().deleteFromId(profileId)
    }

    suspend fun updateProfile(profile: Profile) {
        redditDatabase.profileDao().update(profile)
    }

    //endregion

    //region Save

    suspend fun savePost(post: PostEntity, profileId: Int) {
        post.run {
            this.profileId = profileId
            this.time = System.currentTimeMillis()
            redditDatabase.postDao().upsert(this)
        }
    }

    suspend fun unsavePost(post: PostEntity, profileId: Int) {
        redditDatabase.postDao().deleteFromIdAndProfile(post.id, profileId)
    }

    fun getSavedPosts(profileId: Int): Flow<List<PostEntity>> {
        return redditDatabase.postDao().getSavedPostsFromProfile(profileId)
    }

    fun getSavedPostIds(profileId: Int): Flow<List<String>> {
        return redditDatabase.postDao().getSavedPostIdsFromProfile(profileId)
    }

    suspend fun saveComment(comment: Comment.CommentEntity, profileId: Int) {
        comment.run {
            this.profileId = profileId
            this.time = System.currentTimeMillis()
            redditDatabase.commentDao().upsert(comment)
        }
    }

    suspend fun unsaveComment(comment: Comment.CommentEntity, profileId: Int) {
        redditDatabase.commentDao().deleteFromIdAndProfile(comment.name, profileId)
    }

    fun getSavedComments(profileId: Int): Flow<List<Comment.CommentEntity>> {
        return redditDatabase.commentDao().getSavedCommentsFromProfile(profileId)
    }

    fun getSavedCommentIds(profileId: Int): Flow<List<String>> {
        return redditDatabase.commentDao().getSavedCommentIdsFromProfile(profileId)
    }

    /**
     * Lowercased authors of the saved posts of a profile: the users of the saved Users timeline,
     * and the ones whose profile page shows a filled star.
     *
     * [showNsfw] mirrors the filter the timeline applies to the same posts, so both views agree
     * on a user whose saved posts are all hidden.
     */
    fun getSavedAuthors(profileId: Int, showNsfw: Boolean): Flow<List<String>> {
        return redditDatabase.postDao().getSavedAuthorsFromProfile(profileId, showNsfw)
    }

    /**
     * Removes every saved post of [author] for [profileId], i.e. unstars the user from the
     * profile page.
     */
    suspend fun unsaveUserPosts(author: String, profileId: Int) {
        redditDatabase.postDao().deleteFromAuthorAndProfile(author, profileId)
    }

    //endregion

    //region Saved users

    /**
     * [getUserLatestPosts] for the saved Users sweep, paced by [RedditRateLimiter].
     *
     * The sweep issues one request per saved user, so on a large profile it is the app's biggest
     * source of traffic by far and the one that provokes Reddit's rate limit. One-off callers —
     * the profile page's star, say — go through [getUserLatestPosts] directly and are not made to
     * wait behind it.
     */
    suspend fun getSavedUserLatestPosts(
        user: String,
        limit: Int = USER_LATEST_LIMIT
    ): List<PostData> {
        rateLimiter.acquire()
        return getUserLatestPosts(user, limit)
    }

    /**
     * Cached latest post of every saved user of a profile, as fetched by
     * [getSavedUserLatestPosts]. See [SavedUserPost].
     */
    suspend fun getSavedUserPosts(profileId: Int): List<SavedUserPost> {
        return redditDatabase.savedUserPostDao().getFromProfile(profileId)
    }

    suspend fun cacheSavedUserPost(
        authorKey: String,
        post: PostEntity,
        profileId: Int,
        fetchedAt: Long = System.currentTimeMillis()
    ) {
        redditDatabase.savedUserPostDao().upsert(
            SavedUserPost(authorKey, profileId, fetchedAt, post)
        )
    }

    /**
     * Forgets the cached posts of [authorKeys] for [profileId], i.e. the users whose posts are no
     * longer saved.
     *
     * Takes the users to drop rather than the ones to keep: a profile can have far more saved
     * users than stale entries, and every key here becomes a bound variable. Chunked for the same
     * reason — SQLite refuses a statement with more than 999 of them.
     */
    suspend fun pruneSavedUserPosts(profileId: Int, authorKeys: Collection<String>) {
        authorKeys.chunked(MAX_SQL_VARIABLES).forEach { chunk ->
            redditDatabase.savedUserPostDao().deleteFromProfile(profileId, chunk)
        }
    }

    //endregion

    companion object {
        private const val DEFAULT_LIMIT = 25

        // Enough to skip over posts hidden by the NSFW preference without paging
        private const val USER_LATEST_LIMIT = 10

        // SQLite rejects a statement binding more variables than this, minus the ones the query
        // itself uses
        private const val MAX_SQL_VARIABLES = 900
    }
}
