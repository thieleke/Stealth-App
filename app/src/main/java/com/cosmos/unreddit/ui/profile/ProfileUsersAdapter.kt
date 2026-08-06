package com.cosmos.unreddit.ui.profile

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.cosmos.unreddit.data.model.PostType
import com.cosmos.unreddit.data.model.db.PostEntity
import com.cosmos.unreddit.data.model.preferences.ContentPreferences
import com.cosmos.unreddit.databinding.ItemPostImageBinding
import com.cosmos.unreddit.databinding.ItemPostLinkBinding
import com.cosmos.unreddit.databinding.ItemPostTextBinding
import com.cosmos.unreddit.ui.common.widget.RedditView
import com.cosmos.unreddit.ui.postlist.PostListAdapter
import com.cosmos.unreddit.ui.postlist.PostViewHolder

/**
 * Renders one post per saved user: their most recently saved post. Row identity is the author,
 * not the post, as a user's row changes whenever a newer post of theirs is saved.
 */
class ProfileUsersAdapter(
    private val postClickListener: PostListAdapter.PostClickListener,
    private val onLinkClickListener: RedditView.OnLinkClickListener? = null,
) : ListAdapter<PostEntity, RecyclerView.ViewHolder>(USER_COMPARATOR) {

    var contentPreferences: ContentPreferences = ContentPreferences(
        showNsfw = false,
        showNsfwPreview = false,
        showSpoilerPreview = false,
        largePreview = false
    )
        set(value) {
            if (field.showNsfwPreview != value.showNsfwPreview ||
                field.showSpoilerPreview != value.showSpoilerPreview ||
                field.largePreview != value.largePreview
            ) {
                field = value
                notifyDataSetChanged()
            }
        }

    private val listener = object : PostListAdapter.Listener {
        override fun onClick(position: Int, isLong: Boolean) {
            getPost(position)?.let {
                if (isLong) {
                    postClickListener.onLongClick(it)
                } else {
                    postClickListener.onClick(it)
                }
            }
        }

        override fun onMediaClick(position: Int) {
            getPost(position)?.let {
                when (it.type) {
                    PostType.IMAGE -> postClickListener.onImageClick(it)
                    PostType.LINK -> postClickListener.onLinkClick(it)
                    PostType.VIDEO -> postClickListener.onVideoClick(it)
                    else -> {
                        // ignore
                    }
                }
            }
        }

        override fun onMenuClick(position: Int) {
            getPost(position)?.let {
                postClickListener.onMenuClick(it)
            }
        }

        override fun onSaveClick(position: Int) {
            getPost(position)?.let {
                postClickListener.onSaveClick(it)
                it.saved = !it.saved
                notifyItemChanged(position, it)
            }
        }

        override fun onUserClick(position: Int) {
            getPost(position)?.let {
                postClickListener.onUserClick(it)
            }
        }

        override fun onSubredditClick(position: Int) {
            getPost(position)?.let {
                postClickListener.onSubredditClick(it)
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)

        return when (viewType) {
            // Text post
            PostType.TEXT.value -> PostViewHolder.TextPostViewHolder(
                ItemPostTextBinding.inflate(inflater, parent, false),
                listener,
                onLinkClickListener
            )
            // Image post
            PostType.IMAGE.value -> PostViewHolder.ImagePostViewHolder(
                ItemPostImageBinding.inflate(inflater, parent, false),
                listener
            )
            // Video post
            PostType.VIDEO.value -> PostViewHolder.VideoPostViewHolder(
                ItemPostImageBinding.inflate(inflater, parent, false),
                listener
            )
            // Link post
            PostType.LINK.value -> PostViewHolder.LinkPostViewHolder(
                ItemPostLinkBinding.inflate(inflater, parent, false),
                listener
            )
            else -> throw IllegalArgumentException("Unknown type $viewType")
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (getItemViewType(position)) {
            // Text post
            PostType.TEXT.value -> (holder as PostViewHolder.TextPostViewHolder).bind(
                getItem(position),
                contentPreferences
            )
            // Image post
            PostType.IMAGE.value -> (holder as PostViewHolder.ImagePostViewHolder).bind(
                getItem(position),
                contentPreferences
            )
            // Video post
            PostType.VIDEO.value -> (holder as PostViewHolder.VideoPostViewHolder).bind(
                getItem(position),
                contentPreferences
            )
            // Link post
            PostType.LINK.value -> (holder as PostViewHolder.LinkPostViewHolder).bind(
                getItem(position),
                contentPreferences
            )
            else -> throw IllegalArgumentException("Unknown type")
        }
    }

    override fun getItemViewType(position: Int): Int {
        return getItem(position).type.value
    }

    // Positions come from bindingAdapterPosition, which is NO_POSITION for a recycled holder
    private fun getPost(position: Int): PostEntity? {
        return if (position in 0 until itemCount) getItem(position) else null
    }

    companion object {
        private val USER_COMPARATOR = object : DiffUtil.ItemCallback<PostEntity>() {
            override fun areItemsTheSame(oldItem: PostEntity, newItem: PostEntity): Boolean {
                return oldItem.author.equals(newItem.author, ignoreCase = true)
            }

            override fun areContentsTheSame(oldItem: PostEntity, newItem: PostEntity): Boolean {
                return oldItem == newItem
            }
        }
    }
}
