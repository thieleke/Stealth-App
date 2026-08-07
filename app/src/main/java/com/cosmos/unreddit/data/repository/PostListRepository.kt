package com.cosmos.unreddit.data.repository

import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import com.cosmos.unreddit.data.local.RedditDatabase
import com.cosmos.unreddit.data.model.Comment
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
        pageSize: Int = DEFAULT_LIMIT
    ): Flow<PagingData<Child>> {
        return Pager(PagingConfig(pageSize = pageSize)) {
            SmartPostListDataSource(
                source,
                listOf(subreddit),
                sorting,
                defaultDispatcher,
                mainImmediateDispatcher
            )
        }.flow
    }

    fun getPosts(
        subreddit: List<String>,
        sorting: Sorting,
        pageSize: Int = DEFAULT_LIMIT
    ): Flow<PagingData<Child>> {
        return Pager(PagingConfig(pageSize = pageSize)) {
            SmartPostListDataSource(
                source,
                subreddit,
                sorting,
                defaultDispatcher,
                mainImmediateDispatcher
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
        pageSize: Int = DEFAULT_LIMIT
    ): Flow<PagingData<Child>> {
        return Pager(PagingConfig(pageSize = pageSize)) {
            UserPostsDataSource(source, user, sorting)
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
     * [limit] is passed to the API to keep the response small; sources that cannot honor it
     * (web scraping) return their full page, so it is applied again client-side.
     */
    suspend fun getUserLatestPosts(user: String, limit: Int = USER_LATEST_LIMIT): List<PostData> {
        return source.getUserPosts(user, Sort.NEW, null, null, limit)
            .data
            .children
            .filterIsInstance<PostChild>()
            .take(limit)
            .map { it.data }
    }

    //endregion

    //region Search

    fun searchPost(
        query: String,
        sorting: Sorting,
        pageSize: Int = DEFAULT_LIMIT
    ): Flow<PagingData<Child>> {
        return Pager(PagingConfig(pageSize = pageSize)) {
            SearchPostDataSource(source, query, sorting)
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
        pageSize: Int = DEFAULT_LIMIT
    ): Flow<PagingData<Child>> {
        return Pager(PagingConfig(pageSize = pageSize)) {
            SubredditSearchPostDataSource(source, subreddit, query, sorting)
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

    //endregion

    //region Saved users

    /**
     * Cached latest post of every saved user of a profile, as fetched by
     * [getUserLatestPosts]. See [SavedUserPost].
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
     * Forgets the users of [profileId] that are not in [authorKeys], i.e. those whose posts are no
     * longer saved. A no-op for an empty [authorKeys], as the caller then has nothing to keep and
     * the profile's saved posts are the only thing that populates the cache.
     */
    suspend fun pruneSavedUserPosts(profileId: Int, authorKeys: List<String>) {
        if (authorKeys.isEmpty()) return

        redditDatabase.savedUserPostDao().deleteFromProfileExcept(profileId, authorKeys)
    }

    //endregion

    companion object {
        private const val DEFAULT_LIMIT = 25

        // Enough to skip over posts hidden by the NSFW preference without paging
        private const val USER_LATEST_LIMIT = 10
    }
}
