package com.elvi.tvremote

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.UUID

/** Saved UUIDs survive address and discovery-name changes; TLS pins establish trust. */
internal class SavedTvStore(private val prefs: SharedPreferences) {
    /**
     * Migration copies only a uniquely identified last-successful TV's old host pin. The callback
     * must durably copy (not move) the pin and be safe to retry after a process interruption.
     * A persisted plan keeps the UUIDs identical across those retries.
     */
    fun load(migratePairing: (newId: String, oldHost: String) -> Unit = { _, _ -> }): MutableMap<String, TvDevice> {
        val source = prefs.getString(DEVICES, "[]") ?: "[]"
        if (prefs.getInt(VERSION, 0) >= CURRENT_VERSION) return decode(JSONArray(source))

        val previous = prefs.getString(PENDING, null)?.let { JSONObject(it) }
        val plan = if (previous != null && previous.getString("source") == source) previous else {
            val records = JSONArray(source)
            val migrated = JSONArray()
            for (index in 0 until records.length()) {
                val record = records.getJSONObject(index)
                val legacyId = record.getString("id")
                // Copy fields, including any future metadata, instead of dropping it.
                val item = JSONObject(record.toString())
                    .put("legacyId", legacyId)
                    .put("id", UUID.randomUUID().toString())
                if (!legacyId.startsWith("manual:")) item.put("discoveryId", legacyId)
                migrated.put(item)
            }
            JSONObject().put("source", source).put("devices", migrated).also {
                check(prefs.edit().putString(PENDING, it.toString()).commit()) {
                    "Could not save the TV identity migration."
                }
            }
        }
        val records = plan.getJSONArray("devices")
        val migrated = (0 until records.length()).map { index ->
            val item = records.getJSONObject(index)
            item.getString("legacyId") to decode(item)
        }
        val lastId = prefs.getString("last_used_tv_id", null)?.takeIf { it.isNotBlank() }
        val lastHost = prefs.getString("last_used_tv_host", null)?.takeIf { it.isNotBlank() }
        val lastRecord = migrated.singleOrNull { it.first == lastId }
        if (lastRecord != null && lastHost != null) {
            // Older discovery changed saved addresses before authentication. Even unique
            // current addresses can have swapped occupants, so only the exact last-used
            // record's successful endpoint supplies evidence of legacy pin ownership.
            val ambiguousSource = migrated.any { (_, tv) ->
                tv.id != lastRecord.second.id && legacyHostKey(tv.host) == legacyHostKey(lastHost)
            }
            if (!ambiguousSource) migratePairing(lastRecord.second.id, lastHost)
        }
        // Preserve every other TV, but require explicit pairing to establish its identity.

        // Last-used selection and saved IDs must advance together. The old records remain
        // untouched if a pin copy fails; a retry resumes the same persisted plan.
        val stored = JSONArray()
        for (index in 0 until records.length()) {
            stored.put(JSONObject(records.getJSONObject(index).toString()).also { it.remove("legacyId") })
        }
        val editor = prefs.edit().putString(DEVICES, stored.toString())
            .putInt(VERSION, CURRENT_VERSION).remove(PENDING)
        LastUsedTvStore(prefs).migrate(editor, migrated)
        check(editor.commit()) { "Could not finish the TV identity migration." }
        return migrated.associateTo(linkedMapOf()) { (_, tv) -> tv.id to tv }
    }

    fun save(devices: Collection<TvDevice>) {
        val records = JSONArray()
        devices.forEach { tv ->
            records.put(JSONObject().put("id", tv.id).put("name", tv.name).put("host", tv.host)
                .put("port", tv.port).put("pairingPort", tv.pairingPort)
                .put("discoveryId", tv.discoveryId))
        }
        prefs.edit().putString(DEVICES, records.toString()).putInt(VERSION, CURRENT_VERSION).apply()
    }

    private fun decode(records: JSONArray): MutableMap<String, TvDevice> = linkedMapOf<String, TvDevice>().apply {
        for (index in 0 until records.length()) {
            val tv = decode(records.getJSONObject(index))
            put(tv.id, tv)
        }
    }

    private fun decode(item: JSONObject) = TvDevice(
        id = item.getString("id"), name = item.getString("name"), host = item.getString("host"),
        port = item.optInt("port", 6466), pairingPort = item.optInt("pairingPort", 6467), saved = true,
        discoveryId = item.optString("discoveryId").takeIf { it.isNotBlank() && it != "null" },
    )

    private fun legacyHostKey(host: String): String =
        host.trim().removeSurrounding("[", "]").lowercase(Locale.ROOT)

    private companion object {
        const val DEVICES = "devices"
        const val VERSION = "saved_tv_identity_version"
        const val CURRENT_VERSION = 1
        const val PENDING = "saved_tv_identity_migration"
    }
}
