package com.cosmos.unreddit.data.model

/**
 * Post type filter applied on top of the post list.
 *
 * The filter is a session-level, non-persisted value shared across the main, subreddit and user
 * profile views. It is kept in memory only, so it is reset to [ALL] when the application is
 * exited.
 */
enum class PostTypeFilter {
    /** Show every post (default). */
    ALL,

    /** Show text posts (self posts and links, i.e. non-media posts). */
    TEXT,

    /** Show media posts (images and videos). */
    MEDIA,

    /** Show image posts only. */
    IMAGE,

    /** Show video posts only. */
    VIDEO;

    /**
     * Whether a post of the given [type] should be shown when this filter is active.
     */
    fun matches(type: PostType): Boolean = when (this) {
        ALL -> true
        TEXT -> type == PostType.TEXT || type == PostType.LINK
        MEDIA -> type == PostType.IMAGE || type == PostType.VIDEO
        IMAGE -> type == PostType.IMAGE
        VIDEO -> type == PostType.VIDEO
    }
}
