package com.cosmos.unreddit.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import com.cosmos.unreddit.data.model.db.SavedUserPost

@Dao
abstract class SavedUserPostDao : BaseDao<SavedUserPost> {

    @Query("SELECT * FROM saved_user_post WHERE profile_id = :profileId")
    abstract suspend fun getFromProfile(profileId: Int): List<SavedUserPost>

    /**
     * Drops the entries of the users that are no longer among the saved posts, so the cache does
     * not outlive what it caches.
     */
    @Query(
        "DELETE FROM saved_user_post WHERE profile_id = :profileId " +
                "AND author_key IN (:authorKeys)"
    )
    abstract suspend fun deleteFromProfile(profileId: Int, authorKeys: List<String>)
}
