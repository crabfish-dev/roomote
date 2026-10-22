package com.elvi.tvremote

import android.content.SharedPreferences

/** Remembers identity, not an address that a router can assign to another TV. */
internal class LastUsedTvStore(private val prefs: SharedPreferences) {
    fun get(saved: Collection<TvDevice>): TvDevice? {
        if (!prefs.getBoolean(INITIALIZED, false)) {
            // Older versions did not track use order. Only one saved TV is unambiguous.
            val recordedId = prefs.getString(ID, null)
            val onlyTv = if (recordedId == null && prefs.getString(HOST, null) == null) {
                saved.singleOrNull()
            } else saved.singleOrNull { it.id == recordedId }
            if (onlyTv != null) {
                remember(onlyTv)
                return onlyTv
            }
            prefs.edit().putBoolean(INITIALIZED, true).apply()
        }
        val id = prefs.getString(ID, null) ?: return null
        val selected = saved.singleOrNull { it.id == id } ?: return null
        if (prefs.getString(HOST, null) != selected.host) remember(selected)
        return selected
    }

    fun remember(tv: TvDevice) {
        writeSelection(prefs.edit(), tv).apply()
    }

    fun forget(tv: TvDevice) {
        val id = prefs.getString(ID, null)
        if (id == tv.id || id == null) {
            // Keep the marker so removing one TV cannot silently select another.
            writeSelection(prefs.edit(), null).apply()
        }
    }

    /** Part of SavedTvStore's atomic migration commit, after all pins have been copied. */
    internal fun migrate(editor: SharedPreferences.Editor, migrated: List<Pair<String, TvDevice>>) {
        val id = prefs.getString(ID, null)
        val host = prefs.getString(HOST, null)
        val selected = when {
            id == null && host == null && !prefs.getBoolean(INITIALIZED, false) ->
                migrated.singleOrNull()?.second
            id != null && migrated.any { it.second.id == id } ->
                migrated.singleOrNull { it.second.id == id }?.second
            id != null && migrated.any { it.first == id } -> {
                val exact = migrated.filter { it.first == id }
                (exact.singleOrNull() ?: exact.singleOrNull { it.second.host == host })?.second
            }
            // Address fallback is allowed only during the one-time legacy migration.
            !prefs.getBoolean(STABLE_ID, false) && host != null ->
                migrated.singleOrNull { it.second.host == host }?.second
            else -> null
        }
        writeSelection(editor, selected)
    }

    private fun writeSelection(editor: SharedPreferences.Editor, tv: TvDevice?): SharedPreferences.Editor {
        editor.putBoolean(INITIALIZED, true).putBoolean(STABLE_ID, true)
        return if (tv == null) editor.remove(ID).remove(HOST)
        else editor.putString(ID, tv.id).putString(HOST, tv.host)
    }

    private companion object {
        const val INITIALIZED = "last_used_tv_initialized"
        const val ID = "last_used_tv_id"
        const val HOST = "last_used_tv_host"
        const val STABLE_ID = "last_used_tv_stable_id"
    }
}
