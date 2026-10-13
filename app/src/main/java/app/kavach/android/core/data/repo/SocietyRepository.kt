package app.kavach.android.core.data.repo

import android.content.Context
import app.kavach.android.core.data.model.PowerEvent
import app.kavach.android.core.data.model.PowerSource
import app.kavach.android.core.data.model.PowerState
import app.kavach.android.core.data.model.Society
import app.kavach.android.core.data.model.Zone
import app.kavach.android.core.data.remote.Db
import app.kavach.android.core.data.remote.ErrorCode
import app.kavach.android.core.data.remote.KavachException
import app.kavach.android.core.data.remote.LiveQueries
import app.kavach.android.core.data.remote.Loadable
import app.kavach.android.core.data.remote.table
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.query.Columns
import io.github.jan.supabase.postgrest.query.Order
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/** Society structure: society row, zones (towers, common areas) and power. */
@Singleton
class SocietyRepository @Inject constructor(
    private val db: Db,
    private val live: LiveQueries,
    @ApplicationContext private val context: Context,
    private val json: Json,
) {
    // Zone names are not sensitive. Kept on disk so a resident who is offline can still pick
    // "Somewhere else" and queue a report that names a real zone.
    private val zoneCache by lazy { context.getSharedPreferences("kavach_zone_cache", Context.MODE_PRIVATE) }

    suspend fun society(): Society? = db.call {
        from("societies").select(Columns.raw(SOCIETY_COLUMNS)).decodeSingleOrNull<Society>()
    }

    fun listenSociety(): Flow<Loadable<Society?>> = live.live("society", table("societies")) { society() }

    /** Zones in display order (sort_order, then name). */
    suspend fun zones(): List<Zone> = try {
        db.call {
            from("zones").select(Columns.raw(ZONE_COLUMNS)) {
                order("sort_order", Order.ASCENDING)
                order("name", Order.ASCENDING)
            }.decodeList<Zone>()
        }.also { saveZones(it) }
    } catch (e: KavachException) {
        // Offline / timed out: the last list we saw beats an empty picker
        if (e.code == ErrorCode.Offline || e.code == ErrorCode.Timeout) cachedZones() ?: throw e else throw e
    }

    private fun saveZones(list: List<Zone>) {
        if (list.isEmpty()) return
        runCatching {
            zoneCache.edit().putString("zones", json.encodeToString(ListSerializer(Zone.serializer()), list)).apply()
        }
    }

    private fun cachedZones(): List<Zone>? = runCatching {
        zoneCache.getString("zones", null)?.let { json.decodeFromString(ListSerializer(Zone.serializer()), it) }
    }.getOrNull()?.takeIf { it.isNotEmpty() }

    fun listenZones(): Flow<Loadable<List<Zone>>> = live.live("zones", table("zones")) { zones() }

    /** Admin: manual zone power. */
    suspend fun setZonePower(zoneId: String, state: PowerState) {
        db.rpc("set_zone_power", buildJsonObject { put("p_zone_id", zoneId); put("p_state", state.wire) })
    }

    /** Admin: grid ↔ DG. DG applies tiers (P1/P2 on, P3 rotating, P4 off) and logs power_events. */
    suspend fun setPowerSource(source: PowerSource, note: String? = null) {
        db.rpc("set_power_source", buildJsonObject {
            put("p_source", source.wire)
            put("p_note", note?.takeIf { it.isNotBlank() })
        })
    }

    suspend fun powerEvents(limit: Long = 20): List<PowerEvent> = db.call {
        from("power_events").select(Columns.raw(POWER_EVENT_COLUMNS)) {
            order("switched_at", Order.DESCENDING)
            limit(limit)
        }.decodeList<PowerEvent>()
    }

    fun listenPowerEvents(limit: Long = 10): Flow<Loadable<List<PowerEvent>>> =
        live.live("power-events:$limit", table("power_events")) { powerEvents(limit) }
}
