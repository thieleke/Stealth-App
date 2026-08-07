package com.cosmos.unreddit.ui.base

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cosmos.unreddit.data.model.Comment
import com.cosmos.unreddit.data.model.db.PostEntity
import com.cosmos.unreddit.data.model.db.Profile
import com.cosmos.unreddit.data.model.db.Subscription
import com.cosmos.unreddit.data.repository.PostListRepository
import com.cosmos.unreddit.data.repository.PreferencesRepository
import com.cosmos.unreddit.util.extension.latest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.launch

open class BaseViewModel(
    preferencesRepository: PreferencesRepository,
    private val postListRepository: PostListRepository
) : ViewModel() {

    /**
     * Deduplicated: several flows key their Room queries off this through `flatMapLatest`, and a
     * re-emission of the same profile would cancel and restart every one of them for nothing.
     */
    val currentProfile: SharedFlow<Profile> = preferencesRepository.getCurrentProfile().map {
        postListRepository.getProfile(it)
    }.distinctUntilChanged().shareIn(viewModelScope, SharingStarted.WhileSubscribed(), 1)

    protected val historyIds: Flow<List<String>> = currentProfile.flatMapLatest {
        postListRepository.getHistoryIds(it.id)
    }

    protected val subscriptions: Flow<List<Subscription>> = currentProfile.flatMapLatest {
        postListRepository.getSubscriptions(it.id)
    }

    protected val subscriptionsNames: Flow<List<String>> = currentProfile.flatMapLatest {
        postListRepository.getSubscriptionsNames(it.id)
    }

    protected val savedPostIds: Flow<List<String>> = currentProfile.flatMapLatest {
        postListRepository.getSavedPostIds(it.id)
    }

    fun insertPostInHistory(postId: String) {
        viewModelScope.launch {
            currentProfile.latest?.let {
                postListRepository.insertPostInHistory(postId, it.id)
            }
        }
    }

    fun toggleSavePost(post: PostEntity) {
        viewModelScope.launch {
            currentProfile.latest?.let {
                if (post.saved) {
                    postListRepository.unsavePost(post, it.id)
                } else {
                    postListRepository.savePost(post, it.id)
                }
            }
        }
    }

    fun toggleSaveComment(comment: Comment.CommentEntity) {
        viewModelScope.launch {
            currentProfile.latest?.let {
                if (comment.saved) {
                    postListRepository.unsaveComment(comment, it.id)
                } else {
                    postListRepository.saveComment(comment, it.id)
                }
            }
        }
    }
}
