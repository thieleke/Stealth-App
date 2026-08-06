package com.cosmos.unreddit.data.model

import java.util.concurrent.TimeUnit

/**
 * How long the saved Users timeline keeps a fetched post before going back to the network for it.
 *
 * Fetching is one request per saved user, so this is what keeps the panel from being slow to open.
 * [NONE] never refetches on its own and leaves it entirely to a pull to refresh; a user that has
 * never been fetched is always fetched once, whatever the setting.
 *
 * The stored value is the period in hours.
 */
enum class SavedUsersRefresh(val index: Int, val hours: Int) {
    // Indexes must follow the pref_saved_users_refresh_labels array
    NONE(0, 0),
    TWO_HOURS(1, 2),
    EIGHT_HOURS(2, 8);

    val period: Long
        get() = TimeUnit.HOURS.toMillis(hours.toLong())

    companion object {
        val DEFAULT = EIGHT_HOURS

        fun fromHours(hours: Int): SavedUsersRefresh {
            return values().find { it.hours == hours } ?: DEFAULT
        }

        fun fromIndex(index: Int): SavedUsersRefresh {
            return values().find { it.index == index } ?: DEFAULT
        }
    }
}
