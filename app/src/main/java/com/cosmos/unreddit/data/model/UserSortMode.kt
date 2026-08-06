package com.cosmos.unreddit.data.model

enum class UserSortMode(val value: Int) {
    ALPHABETICAL(0),
    CHRONOLOGICAL(1);

    companion object {
        fun fromValue(value: Int): UserSortMode =
            values().find { it.value == value } ?: ALPHABETICAL
    }
}
