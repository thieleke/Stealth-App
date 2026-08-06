package com.cosmos.unreddit.data.model.preferences

import androidx.datastore.preferences.core.intPreferencesKey

data class ProfilePreferences(
    val currentProfile: Int
) {
    object PreferencesKeys {
        val CURRENT_PROFILE = intPreferencesKey("current_profile")
        val SAVED_LAST_TAB = intPreferencesKey("saved_last_tab")
        val SAVED_USERS_SORT = intPreferencesKey("saved_users_sort")
    }
}
