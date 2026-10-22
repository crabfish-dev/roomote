package com.elvi.tvremote

import android.content.Context
import android.content.SharedPreferences
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class LastUsedTvStoreTest {
    private lateinit var context: Context
    private lateinit var preferenceName: String
    private lateinit var prefs: SharedPreferences
    private lateinit var store: LastUsedTvStore
    private val livingRoom = TvDevice("living-room", "Living room", "192.0.2.10", saved = true)
    private val bedroom = TvDevice("bedroom", "Bedroom", "192.0.2.20", saved = true)

    @Before fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        preferenceName = "last_used_tv_test_${UUID.randomUUID()}"
        prefs = context.getSharedPreferences(preferenceName, Context.MODE_PRIVATE)
        store = LastUsedTvStore(prefs)
    }

    @After fun tearDown() {
        context.deleteSharedPreferences(preferenceName)
    }

    @Test fun remembersMostRecentSuccessfulTvAcrossInstances() {
        store.remember(livingRoom)
        store.remember(bedroom)

        val restored = LastUsedTvStore(context.getSharedPreferences(preferenceName, Context.MODE_PRIVATE))
        assertEquals(bedroom, restored.get(listOf(livingRoom, bedroom)))
    }

    @Test fun migratesTheOnlySavedTvOnce() {
        assertEquals(livingRoom, store.get(listOf(livingRoom)))

        val restored = LastUsedTvStore(prefs)
        assertEquals(livingRoom, restored.get(listOf(bedroom, livingRoom)))
        assertNull(restored.get(listOf(bedroom)))
    }

    @Test fun doesNotGuessAmongMultipleSavedTvs() {
        assertNull(store.get(listOf(livingRoom, bedroom)))
        // Removing one of the ambiguous candidates must not select the other.
        assertNull(LastUsedTvStore(prefs).get(listOf(bedroom)))
    }

    @Test fun noSavedTvsHasNoAutomaticTarget() {
        assertNull(store.get(emptyList()))
        store.remember(livingRoom)
        assertEquals(livingRoom, store.get(listOf(livingRoom)))
    }

    @Test fun resolvesStableIdBeforeAnAddressAlias() {
        store.remember(livingRoom)
        val moved = livingRoom.copy(host = "192.0.2.30")
        val oldAddressAlias = livingRoom.copy(id = "manual:${livingRoom.host}")

        assertEquals(moved, store.get(listOf(oldAddressAlias, moved)))
    }

    @Test fun doesNotSelectAnotherIdentityAtTheRememberedAddress() {
        store.remember(livingRoom)
        val alias = livingRoom.copy(id = "manual:${livingRoom.host}")

        assertNull(store.get(listOf(bedroom, alias)))
    }

    @Test fun forgettingAnotherIdentityAtTheSameAddressPreservesSelection() {
        store.remember(livingRoom)
        store.forget(livingRoom.copy(id = "manual:${livingRoom.host}"))

        assertEquals(livingRoom, LastUsedTvStore(prefs).get(listOf(livingRoom, bedroom)))
        store.remember(bedroom)
        assertEquals(bedroom, store.get(listOf(bedroom)))
    }

    @Test fun resolvesChangedAddressByIdentityAndUpdatesCachedHost() {
        store.remember(livingRoom)
        val moved = livingRoom.copy(host = "192.0.2.30")

        assertEquals(moved, store.get(listOf(moved)))
        assertEquals(moved.host, prefs.getString("last_used_tv_host", null))
    }

    @Test fun forgettingByIdClearsAnOldAddress() {
        store.remember(livingRoom)
        store.forget(livingRoom.copy(host = "192.0.2.30"))

        assertNull(LastUsedTvStore(prefs).get(listOf(bedroom)))
    }

    @Test fun forgettingAnUnrelatedTvPreservesTheLastUsedTv() {
        store.remember(livingRoom)
        store.forget(bedroom)

        assertEquals(livingRoom, store.get(listOf(livingRoom)))
    }

    @Test fun forgettingBeforeMigrationDoesNotSelectTheRemainingTv() {
        store.forget(livingRoom)

        assertNull(LastUsedTvStore(prefs).get(listOf(bedroom)))
    }

    @Test fun unknownRecordedTargetDoesNotFallBackToTheOnlySavedTv() {
        store.remember(livingRoom)

        assertNull(LastUsedTvStore(prefs).get(listOf(bedroom)))
    }

    @Test fun missingInitializationMarkerDoesNotReplaceAnExistingSelection() {
        prefs.edit().putString("last_used_tv_id", livingRoom.id)
            .putString("last_used_tv_host", livingRoom.host).commit()

        assertNull(store.get(listOf(bedroom)))
    }
}
