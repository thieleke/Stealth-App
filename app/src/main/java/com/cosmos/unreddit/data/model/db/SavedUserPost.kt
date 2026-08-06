package com.cosmos.unreddit.data.model.db

import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

/**
 * Cached result of the network lookup behind the saved Users timeline: the newest post found for
 * one author. Without it, reopening the panel re-queries every saved user, which is slow once
 * there are more than a handful of them.
 */
@Entity(
    tableName = "saved_user_post",
    primaryKeys = ["author_key", "profile_id"],
    indices = [Index("profile_id")],
    foreignKeys = [
        ForeignKey(
            entity = Profile::class,
            parentColumns = ["id"],
            childColumns = ["profile_id"],
            onDelete = ForeignKey.CASCADE
        )
    ]
)
data class SavedUserPost(
    /**
     * Author of the saved post this entry was fetched for, lowercased: Reddit usernames are
     * case-insensitive, and the fetched post may spell the name differently.
     */
    @ColumnInfo(name = "author_key")
    val authorKey: String,

    @ColumnInfo(name = "profile_id")
    val profileId: Int,

    /**
     * When the post was fetched. Entries older than the configured
     * [com.cosmos.unreddit.data.model.SavedUsersRefresh] period are fetched again.
     */
    @ColumnInfo(name = "fetched_at")
    val fetchedAt: Long,

    @Embedded(prefix = "post_")
    val post: PostEntity
)
