package com.elvi.tvremote

import android.content.Context
import android.content.SharedPreferences
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class SavedTvStoreTest {
    private lateinit var context: Context
    private lateinit var preferenceName: String
    private lateinit var prefs: SharedPreferences
    private lateinit var store: SavedTvStore
    private val livingRoom = TvDevice("Living-room._androidtvremote2", "Living room", "192.0.2.10", 16466, 16467)
    private val bedroom = TvDevice("manual:192.0.2.20", "Bedroom", "192.0.2.20")

    @Before fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        preferenceName = "saved_tv_test_${UUID.randomUUID()}"
        prefs = context.getSharedPreferences(preferenceName, Context.MODE_PRIVATE)
        store = SavedTvStore(prefs)
    }

    @After fun tearDown() { context.deleteSharedPreferences(preferenceName) }

    @Test fun migratesLegacyRecordsAndDiscoveryHintsWithoutLosingAddressesOrPorts() {
        legacy(livingRoom, bedroom)
        lastUsed(livingRoom.id, livingRoom.host)
        val copied = mutableMapOf<String, String>()

        val result = store.load { id, host -> copied[id] = host }.values.toList()

        assertEquals(2, result.size)
        result.forEach { assertEquals(it.id, UUID.fromString(it.id).toString()); assertTrue(it.saved) }
        val first = result.first()
        assertNotEquals(livingRoom.id, first.id)
        assertEquals(livingRoom.name, first.name)
        assertEquals(livingRoom.host, first.host)
        assertEquals(livingRoom.port, first.port)
        assertEquals(livingRoom.pairingPort, first.pairingPort)
        assertEquals(livingRoom.id, first.discoveryId)
        assertNull(result.last().discoveryId)
        assertEquals(mapOf(first.id to livingRoom.host), copied)
        assertEquals(1, prefs.getInt("saved_tv_identity_version", 0))
        assertFalse(prefs.contains("saved_tv_identity_migration"))
    }

    @Test fun migrationIsOneTimeAndNeverChangesStableIdsAgain() {
        legacy(livingRoom)
        val migrated = store.load()
        val source = prefs.getString("devices", null)

        val restored = SavedTvStore(prefs).load { _, _ -> error("Already migrated") }

        assertEquals(migrated, restored)
        assertEquals(source, prefs.getString("devices", null))
        assertEquals(migrated.values.single(), LastUsedTvStore(prefs).get(restored.values))
    }

    @Test fun retainsSeparateIdentitiesWhenTvNamesAndAddressesAreTheSame() {
        legacy(livingRoom, bedroom.copy(name = livingRoom.name, host = livingRoom.host))
        val copied = mutableListOf<String>()

        val saved = store.load { id, _ -> copied += id }.values.toList()

        assertEquals(2, saved.size)
        assertNotEquals(saved.first().id, saved.last().id)
        assertEquals(saved.first().host, saved.last().host)
        assertTrue("Ambiguous host pins cannot establish either TV's identity", copied.isEmpty())
        assertNull(LastUsedTvStore(prefs).get(saved))
    }

    @Test fun unambiguousLastSuccessfulTvCanMigrateWhileOtherRecordsShareAnAddress() {
        legacy(livingRoom.copy(host = "TV.LOCAL"), bedroom.copy(host = "tv.local"),
            TvDevice("unique-service", "Third TV", "192.0.2.50"))
        lastUsed("unique-service", "192.0.2.50")
        val copied = mutableListOf<String>()

        val saved = store.load { _, host -> copied += host }.values

        assertEquals(3, saved.size)
        assertEquals(listOf("192.0.2.50"), copied)
    }

    @Test fun lastSuccessfulHostWithCaseInsensitiveOtherOwnerDoesNotCopyPin() {
        legacy(livingRoom.copy(host = "192.0.2.30"), bedroom.copy(host = "tv.local"))
        lastUsed(livingRoom.id, "TV.LOCAL")
        val copied = mutableListOf<String>()

        val saved = store.load { id, _ -> copied += id }.values

        assertEquals(2, saved.size)
        assertTrue(copied.isEmpty())
    }

    @Test fun swappedNonLastUsedAddressesCannotCopyTheOtherTvsPin() {
        val thirdTv = TvDevice("third-service", "Third TV", "192.0.2.50")
        legacy(livingRoom.copy(host = bedroom.host), bedroom.copy(host = livingRoom.host), thirdTv)
        lastUsed(thirdTv.id, thirdTv.host)
        val copied = mutableMapOf<String, String>()

        val saved = store.load { id, host -> copied[id] = host }.values
        val restoredThird = saved.single { it.name == thirdTv.name }

        assertEquals(3, saved.size)
        assertEquals(mapOf(restoredThird.id to thirdTv.host), copied)
        assertEquals(bedroom.host, saved.single { it.name == livingRoom.name }.host)
        assertEquals(livingRoom.host, saved.single { it.name == bedroom.name }.host)
    }

    @Test fun uniqueAddressesAndManualIdsWithoutSuccessfulIdentityEvidenceDoNotCopyPins() {
        legacy(livingRoom, bedroom)
        val copied = mutableListOf<String>()

        val saved = store.load { id, _ -> copied += id }.values

        assertEquals(2, saved.size)
        assertTrue(copied.isEmpty())
    }

    @Test fun duplicateLegacyIdsCannotEstablishWhichTvWasLastUsed() {
        legacy(livingRoom, bedroom.copy(id = livingRoom.id))
        lastUsed(livingRoom.id, livingRoom.host)
        val copied = mutableListOf<String>()

        val saved = store.load { id, _ -> copied += id }.values

        assertEquals(2, saved.size)
        assertTrue(copied.isEmpty())
    }

    @Test fun lastSuccessfulHostSuppliesPinWhenLegacyDiscoveryAlreadyChangedTheEndpoint() {
        val discovered = livingRoom.copy(host = "192.0.2.30")
        legacy(discovered, bedroom)
        lastUsed(livingRoom.id, livingRoom.host)
        val copied = mutableMapOf<String, String>()

        val saved = store.load { id, host -> copied[id] = host }.values
        val restored = saved.single { it.name == livingRoom.name }

        assertEquals("192.0.2.30", restored.host)
        assertEquals(livingRoom.host, copied[restored.id])
        assertEquals(restored.id, LastUsedTvStore(prefs).get(saved)?.id)
    }

    @Test fun lastSuccessfulHostCannotCopyTheSameLegacyPinIntoAnotherTv() {
        legacy(livingRoom.copy(host = "192.0.2.30"), bedroom.copy(host = livingRoom.host))
        lastUsed(livingRoom.id, livingRoom.host)
        val copied = mutableListOf<String>()

        val saved = store.load { id, _ -> copied += id }.values

        assertEquals(2, saved.size)
        assertTrue(copied.isEmpty())
    }

    @Test fun remapsExactLastUsedIdBeforeAnAddressSharedByAnotherTv() {
        legacy(livingRoom, bedroom.copy(host = livingRoom.host))
        lastUsed(livingRoom.id, livingRoom.host)

        val saved = store.load().values

        assertEquals(livingRoom.name, LastUsedTvStore(prefs).get(saved)?.name)
        assertNotEquals(livingRoom.id, prefs.getString("last_used_tv_id", null))
    }

    @Test fun remapsLegacyAddressAliasOnlyWhenItIsUnambiguous() {
        legacy(livingRoom, bedroom)
        lastUsed("older-alias", bedroom.host)

        val saved = store.load().values

        assertEquals(bedroom.name, LastUsedTvStore(prefs).get(saved)?.name)
    }

    @Test fun clearsAmbiguousLegacyAddressAndDoesNotChooseTheRemainingTvLater() {
        legacy(livingRoom, bedroom.copy(host = livingRoom.host))
        lastUsed("older-alias", livingRoom.host)

        val saved = store.load().values

        assertNull(LastUsedTvStore(prefs).get(saved))
        assertNull(prefs.getString("last_used_tv_id", null))
        assertNull(LastUsedTvStore(prefs).get(listOf(saved.first())))
    }

    @Test fun missingStableSelectionNeverFallsBackToTheReusedAddressDuringMigration() {
        legacy(livingRoom)
        LastUsedTvStore(prefs).remember(livingRoom.copy(id = UUID.randomUUID().toString()))

        val saved = store.load().values

        assertNull(LastUsedTvStore(prefs).get(saved))
    }

    @Test fun pinCopyFailureLeavesLegacyStateAndResumesTheSameUuidPlan() {
        legacy(livingRoom, bedroom)
        lastUsed(bedroom.id, bedroom.host)
        val source = prefs.getString("devices", null)
        var firstId: String? = null
        try {
            store.load { id, _ -> firstId = id; error("Simulated interruption after copying a pin") }
            error("Expected interrupted migration")
        } catch (expected: IllegalStateException) {
            assertEquals("Simulated interruption after copying a pin", expected.message)
        }
        assertEquals(source, prefs.getString("devices", null))
        assertEquals(bedroom.id, prefs.getString("last_used_tv_id", null))
        assertEquals(0, prefs.getInt("saved_tv_identity_version", 0))
        assertTrue(prefs.contains("saved_tv_identity_migration"))
        val retriedIds = mutableListOf<String>()

        val saved = SavedTvStore(prefs).load { id, _ -> retriedIds += id }

        assertEquals(firstId, retriedIds.first())
        assertEquals(1, retriedIds.size)
        assertEquals(bedroom.name, LastUsedTvStore(prefs).get(saved.values)?.name)
        assertFalse(prefs.contains("saved_tv_identity_migration"))
    }

    @Test fun interruptedPlanDoesNotOverrideNewerLegacyData() {
        legacy(livingRoom)
        lastUsed(livingRoom.id, livingRoom.host)
        runCatching { store.load { _, _ -> error("Interrupted") } }
        assertTrue(prefs.contains("saved_tv_identity_migration"))
        legacy(livingRoom, bedroom)

        val saved = store.load().values

        assertEquals(setOf(livingRoom.name, bedroom.name), saved.map { it.name }.toSet())
    }

    @Test fun stableSaveRoundTripPreservesIdentityAfterAnAddressAndNameChange() {
        val original = livingRoom.copy(id = UUID.randomUUID().toString(), saved = true, discoveryId = livingRoom.id)
        store.save(listOf(original))
        LastUsedTvStore(prefs).remember(original)
        val moved = original.copy(host = "192.0.2.30", name = "Renamed TV", discoveryId = "New service name")
        store.save(listOf(moved))

        val restored = SavedTvStore(prefs).load { _, _ -> error("Stable records do not migrate") }.values

        assertEquals(listOf(moved), restored.toList())
        assertEquals(moved, LastUsedTvStore(prefs).get(restored))
    }

    @Test fun malformedLegacyDataDoesNotOverwriteTheSavedRecords() {
        prefs.edit().putString("devices", "not-json").commit()

        assertTrue(runCatching { store.load() }.isFailure)

        assertEquals("not-json", prefs.getString("devices", null))
        assertEquals(0, prefs.getInt("saved_tv_identity_version", 0))
    }

    private fun legacy(vararg devices: TvDevice) {
        val records = JSONArray()
        devices.forEach { tv ->
            records.put(JSONObject().put("id", tv.id).put("name", tv.name).put("host", tv.host)
                .put("port", tv.port).put("pairingPort", tv.pairingPort))
        }
        prefs.edit().putString("devices", records.toString()).commit()
    }

    private fun lastUsed(id: String, host: String) {
        prefs.edit().putBoolean("last_used_tv_initialized", true)
            .putString("last_used_tv_id", id).putString("last_used_tv_host", host).commit()
    }
}
