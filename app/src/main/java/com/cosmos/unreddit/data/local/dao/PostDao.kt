package com.cosmos.unreddit.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import com.cosmos.unreddit.data.model.db.PostEntity
import kotlinx.coroutines.flow.Flow

@Dao
abstract class PostDao : BaseDao<PostEntity> {

    @Query("DELETE FROM post WHERE id = :id AND profile_id = :profileId")
    abstract suspend fun deleteFromIdAndProfile(id: String, profileId: Int)

    @Query("DELETE FROM post WHERE profile_id = :profileId")
    abstract suspend fun deleteFromProfile(profileId: Int)

    @Query("SELECT id FROM post WHERE profile_id = :profileId")
    abstract fun getSavedPostIdsFromProfile(profileId: Int): Flow<List<String>>

    /**
     * Lowercased authors of the saved posts of a profile: the users shown in the saved Users
     * timeline, and the ones whose profile page shows a filled star.
     *
     * [showNsfw] applies the same filter the timeline applies to those posts, so a user whose
     * only saved posts are hidden by the preference is absent from both.
     */
    @Query(
        "SELECT DISTINCT LOWER(author) FROM post " +
            "WHERE profile_id = :profileId AND (:showNsfw OR nsfw = 0)"
    )
    abstract fun getSavedAuthorsFromProfile(profileId: Int, showNsfw: Boolean): Flow<List<String>>

    /**
     * Removes every saved post of [author], i.e. unstars the user from the profile page.
     *
     * Both sides of the comparison are lowercased here rather than by the caller, so the match
     * cannot depend on how the author was spelled when the post was saved.
     */
    @Query("DELETE FROM post WHERE profile_id = :profileId AND LOWER(author) = LOWER(:author)")
    abstract suspend fun deleteFromAuthorAndProfile(author: String, profileId: Int)

    @Query("SELECT * FROM post WHERE profile_id = :profileId")
    abstract fun getSavedPostsFromProfile(profileId: Int): Flow<List<PostEntity>>
}
