package com.elvi.tvremote

import android.content.SharedPreferences

/** Remembers the last successful connection, independently of the saved TV list. */
internal class LastUsedTvStore(private val prefs: SharedPreferences) {
    fun get(saved: Collection<TvDevice>): TvDevice? {
        if (!prefs.getBoolean(INITIALIZED, false)) {
            // Older versions did not track use order. Only one saved TV is unambiguous.
            val onlyTv = saved.singleOrNull()
            if (onlyTv != null) {
                remember(onlyTv)
                return onlyTv
            }
            prefs.edit().putBoolean(INITIALIZED, true).apply()
        }
        val id = prefs.getString(ID, null)
        val host = prefs.getString(HOST, null)
        return saved.firstOrNull { it.id == id }
            ?: saved.firstOrNull { it.host == host }
    }

    fun remember(tv: TvDevice) {
        prefs.edit()
            .putBoolean(INITIALIZED, true)
            .putString(ID, tv.id)
            .putString(HOST, tv.host)
            .apply()
    }

    fun forget(tv: TvDevice) {
        val id = prefs.getString(ID, null)
        val host = prefs.getString(HOST, null)
        if (id == tv.id || host == tv.host || (id == null && host == null)) {
            // Keep the marker so removing one TV cannot silently select another.
            prefs.edit()
                .putBoolean(INITIALIZED, true)
                .remove(ID)
                .remove(HOST)
                .apply()
        }
    }

    private companion object {
        const val INITIALIZED = "last_used_tv_initialized"
        const val ID = "last_used_tv_id"
        const val HOST = "last_used_tv_host"
    }
}
