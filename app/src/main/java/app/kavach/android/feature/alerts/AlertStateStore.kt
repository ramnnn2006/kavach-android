package app.kavach.android.feature.alerts

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Duration
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

/**
 * What was already notified, so a restarted service and the catch-up worker never repeat a
 * notification. Two things are kept (in a small SharedPreferences file):
 *
 *  - seen keys ("inc:ID:new", "inc:ID:status:resolved", "notice:ID", "check:ID", "medical:ID", ...),
 *    each with the time it was last seen as a candidate. [claim] is atomic, so the service and the
 *    worker can run at the same time and only one of them shows a given notification.
 *  - per-source "last seen" times: when a source (queue, my reports, notices, ...) was last read
 *    successfully. Events newer than this were missed while nothing was running and are
 *    shown by the catch-up.
 *
 * Keys that are still candidates (an old resolved report in "my reports", a pinned notice) are
 * refreshed by [markSeen] about once a day, so pruning only drops keys whose item is gone and can
 * never announce an old item again. Pruning happens in [ensureOwner], which the engine calls before
 * each (re)subscription.
 *
 * Everything is cleared when a different person signs in. Writes use apply() (asynchronous) and
 * are made only when something changed.
 */
@Singleton
class AlertStateStore @Inject constructor(@ApplicationContext context: Context) {
    private val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    private val lock = Any()

    /** In-memory copy of the seen keys (key → time last recorded), loaded on first use. */
    private var seen: HashMap<String, Long>? = null

    /** Call before using the store for [userId]: forgets everything that belonged to somebody else. */
    fun ensureOwner(userId: String) {
        synchronized(lock) {
            if (prefs.getString(OWNER, null) != userId) {
                prefs.edit().clear().putString(OWNER, userId).apply()
                seen = HashMap()
            } else {
                pruneLocked()
            }
        }
    }

    fun seenKeys(): Set<String> = synchronized(lock) { HashSet(seenLocked().keys) }

    /** Records [key]. True when it was not recorded before, so the caller may show it. */
    fun claim(key: String): Boolean = synchronized(lock) {
        val map = seenLocked()
        if (key in map) {
            false
        } else {
            val now = System.currentTimeMillis()
            map[key] = now
            prefs.edit().putLong(SEEN + key, now).apply()
            true
        }
    }

    /**
     * Records keys without showing anything (baseline), and refreshes the time of keys that are
     * still candidates when it is older than [TOUCH] (so they are not pruned while still in use).
     */
    fun markSeen(keys: Collection<String>) {
        if (keys.isEmpty()) return
        synchronized(lock) {
            val map = seenLocked()
            val now = System.currentTimeMillis()
            val touchBefore = now - TOUCH.toMillis()
            val edit = prefs.edit()
            var changed = false
            for (key in keys) {
                val at = map[key]
                if (at == null || at < touchBefore) {
                    map[key] = now
                    edit.putLong(SEEN + key, now)
                    changed = true
                }
            }
            if (changed) edit.apply()
        }
    }

    fun lastSeen(source: String): Instant? = synchronized(lock) {
        prefs.getLong(LAST + source, 0L).takeIf { it > 0L }?.let { Instant.ofEpochMilli(it) }
    }

    /**
     * Stores when [source] was last read. Written at most every few minutes per source (the
     * medical poll alone reads every 30 s, and each write rewrites the whole file): an older stored
     * time only makes the next first snapshot look a little further back, and the seen keys keep
     * that from repeating anything.
     */
    fun setLastSeen(source: String, at: Instant) {
        synchronized(lock) {
            val ms = at.toEpochMilli()
            if (abs(ms - prefs.getLong(LAST + source, 0L)) < LAST_SEEN_STEP.toMillis()) return
            prefs.edit().putLong(LAST + source, ms).apply()
        }
    }

    /** Forget everything (sign-out). */
    fun clear() {
        synchronized(lock) {
            prefs.edit().clear().apply()
            seen = HashMap()
        }
    }

    private fun seenLocked(): HashMap<String, Long> =
        seen ?: HashMap<String, Long>().also { map ->
            for ((k, v) in prefs.all) {
                if (k.startsWith(SEEN) && v is Long) map[k.removePrefix(SEEN)] = v
            }
            seen = map
        }

    private fun pruneLocked() {
        val map = seenLocked()
        val oldest = System.currentTimeMillis() - KEEP.toMillis()
        val edit = prefs.edit()
        var changed = false
        val entries = map.entries.iterator()
        while (entries.hasNext()) {
            val entry = entries.next()
            if (entry.value < oldest) {
                entries.remove()
                edit.remove(SEEN + entry.key)
                changed = true
            }
        }
        if (changed) edit.apply()
    }

    private companion object {
        const val FILE = "kavach_alerts_state"
        const val OWNER = "owner"
        const val SEEN = "seen:"
        const val LAST = "last:"

        /** Keys not seen as a candidate for this long are dropped (longest TTL is 24 h). */
        val KEEP: Duration = Duration.ofDays(3)

        /** Keys still in use get their time refreshed when it is older than this. */
        val TOUCH: Duration = Duration.ofDays(1)

        /** Smallest change of a "last seen" time that is written. */
        val LAST_SEEN_STEP: Duration = Duration.ofMinutes(5)
    }
}
