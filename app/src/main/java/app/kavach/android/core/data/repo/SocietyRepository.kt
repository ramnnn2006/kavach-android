package app.kavach.android.core.data.repo

import app.kavach.android.core.data.model.PowerEvent
import app.kavach.android.core.data.model.PowerSource
import app.kavach.android.core.data.model.PowerState
import app.kavach.android.core.data.model.Society
import app.kavach.android.core.data.model.Zone
import app.kavach.android.core.data.remote.Db
import app.kavach.android.core.data.remote.LiveQueries
import app.kavach.android.core.data.remote.Loadable
import app.kavach.android.core.data.remote.table
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.query.Columns
import io.github.jan.supabase.postgrest.query.Order
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import javax.inject.Inject
import javax.inject.Singleton

/** Society structure: society row, zones (towers, common areas) and power. */
@Singleton
class SocietyRepository @Inject constructor(
    private val db: Db,
    private val live: LiveQueries,
) {
    suspend fun society(): Society? = db.call {
        from("societies").select(Columns.raw(SOCIETY_COLUMNS)).decodeSingleOrNull<Society>()
    }

    fun listenSociety(): Flow<Loadable<Society?>> = live.live("society", table("societies")) { society() }

    /** Zones in display order (sort_order, then name). */
    suspend fun zones(): List<Zone> = db.call {
        from("zones").select(Columns.raw(ZONE_COLUMNS)) {
            order("sort_order", Order.ASCENDING)
            order("name", Order.ASCENDING)
        }.decodeList<Zone>()
    }

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
