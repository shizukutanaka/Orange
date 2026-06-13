package com.orange.apple

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the notification rate limiter. Uses an in-memory fake
 * SharedPreferences to avoid Android/Robolectric for what is, at its core,
 * a pure counter-and-window test.
 */
class NotificationRateLimiterTest {

    @Test fun first_notification_fires() {
        val p = FakePrefs()
        assertTrue(NotificationRateLimiter.shouldNotify(p, "+111", 1000L))
    }

    @Test fun same_number_within_window_does_not_fire_again() {
        val p = FakePrefs()
        NotificationRateLimiter.shouldNotify(p, "+111", 1000L)
        assertFalse(NotificationRateLimiter.shouldNotify(p, "+111", 2000L))
    }

    @Test fun five_distinct_numbers_all_fire() {
        val p = FakePrefs()
        repeat(5) { i ->
            assertTrue(NotificationRateLimiter.shouldNotify(p, "+$i", 1000L + i))
        }
    }

    @Test fun sixth_distinct_number_in_window_is_silenced() {
        val p = FakePrefs()
        repeat(5) { i ->
            NotificationRateLimiter.shouldNotify(p, "+$i", 1000L + i)
        }
        assertFalse(NotificationRateLimiter.shouldNotify(p, "+5", 1100L))
    }

    @Test fun window_rolls_over_allowing_new_notifications() {
        val p = FakePrefs()
        repeat(5) { i ->
            NotificationRateLimiter.shouldNotify(p, "+$i", 1000L + i)
        }
        val afterWindow = 1000L + NotificationRateLimiter.WINDOW_MS + 1
        assertTrue(NotificationRateLimiter.shouldNotify(p, "+9", afterWindow))
    }

    @Test fun withheld_number_empty_string_deduped_within_window() {
        // Withheld calls arrive with number = "". The space-separated serialization
        // drops empty strings via filter { isNotBlank() }, so without a sentinel
        // the per-number dedup fails and 5 withheld notifications fire per window.
        val p = FakePrefs()
        assertTrue(NotificationRateLimiter.shouldNotify(p, "", 1000L))
        // Second withheld call in the same window must be suppressed.
        assertFalse(NotificationRateLimiter.shouldNotify(p, "", 2000L))
        assertFalse(NotificationRateLimiter.shouldNotify(p, "", 3000L))
    }
}

/**
 * Minimal in-memory SharedPreferences for unit testing.
 * Only implements the methods the code under test actually calls.
 */
internal class FakePrefs : SharedPreferences {

    private val store = mutableMapOf<String, Any?>()

    override fun getAll(): MutableMap<String, *> = store.toMutableMap()
    override fun getString(k: String?, d: String?): String? = store[k] as? String ?: d
    override fun getStringSet(k: String?, d: MutableSet<String>?) =
        (store[k] as? Set<String>)?.toMutableSet() ?: d
    override fun getInt(k: String?, d: Int): Int = (store[k] as? Int) ?: d
    override fun getLong(k: String?, d: Long): Long = (store[k] as? Long) ?: d
    override fun getFloat(k: String?, d: Float): Float = (store[k] as? Float) ?: d
    override fun getBoolean(k: String?, d: Boolean): Boolean = (store[k] as? Boolean) ?: d
    override fun contains(k: String?): Boolean = store.containsKey(k)
    override fun registerOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) {}
    override fun unregisterOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) {}

    override fun edit(): SharedPreferences.Editor = FakeEditor()

    inner class FakeEditor : SharedPreferences.Editor {
        private val pending = mutableMapOf<String, Any?>()
        private val removals = mutableSetOf<String>()
        private var clearAll = false

        override fun putString(k: String, v: String?) = apply { pending[k] = v }
        override fun putStringSet(k: String, v: MutableSet<String>?) = apply { pending[k] = v }
        override fun putInt(k: String, v: Int) = apply { pending[k] = v }
        override fun putLong(k: String, v: Long) = apply { pending[k] = v }
        override fun putFloat(k: String, v: Float) = apply { pending[k] = v }
        override fun putBoolean(k: String, v: Boolean) = apply { pending[k] = v }
        override fun remove(k: String) = apply { removals.add(k) }
        override fun clear() = apply { clearAll = true }
        override fun apply() { commit() }
        override fun commit(): Boolean {
            if (clearAll) store.clear()
            removals.forEach(store::remove)
            store.putAll(pending)
            return true
        }
    }
}
