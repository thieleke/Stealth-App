package com.cosmos.unreddit.util.extension

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import java.io.IOException

suspend fun <T> DataStore<Preferences>.setValue(key: Preferences.Key<T>, value: T) {
    edit { preferences ->
        preferences[key] = value
    }
}

/**
 * DataStore emits the whole [Preferences] again after *any* write, so without
 * [distinctUntilChanged] every preference flow re-emits when an unrelated key is written. That
 * turns a single write into a restart of every pipeline built on a preference, which is both
 * wasteful and, for anything keyed off `flatMapLatest`, visible as flicker or dropped work.
 */
fun <T> DataStore<Preferences>.getValue(key: Preferences.Key<T>, defaultValue: T): Flow<T> {
    return data.catch { exception ->
        if (exception is IOException) {
            emit(emptyPreferences())
        } else {
            throw exception
        }
    }.map { preferences ->
        preferences[key] ?: defaultValue
    }.distinctUntilChanged()
}
