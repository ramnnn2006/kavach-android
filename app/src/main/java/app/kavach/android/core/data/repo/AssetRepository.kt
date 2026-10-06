package app.kavach.android.core.data.repo

import app.kavach.android.core.data.model.Asset
import app.kavach.android.core.data.model.AssetKind
import app.kavach.android.core.data.model.AssetState
import app.kavach.android.core.data.model.ComplianceItem
import app.kavach.android.core.data.remote.Db
import app.kavach.android.core.data.remote.LiveQueries
import app.kavach.android.core.data.remote.Loadable
import app.kavach.android.core.data.remote.table
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.query.Columns
import io.github.jan.supabase.postgrest.query.Order
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

/** Equipment (lifts, DG, pumps…) and compliance checks. */
@Singleton
class AssetRepository @Inject constructor(
    private val db: Db,
    private val live: LiveQueries,
) {
    suspend fun assets(zoneId: String? = null, kind: AssetKind? = null): List<Asset> = db.call {
        from("assets").select(Columns.raw(ASSET_COLUMNS)) {
            filter {
                zoneId?.let { eq("zone_id", it) }
                kind?.let { eq("kind", it.wire) }
            }
            order("kind", Order.ASCENDING)
            order("name", Order.ASCENDING)
        }.decodeList<Asset>()
    }

    fun listenAssets(zoneId: String? = null, kind: AssetKind? = null): Flow<Loadable<List<Asset>>> =
        live.live("assets:$zoneId:$kind", table("assets")) { assets(zoneId, kind) }

    /** Admin, or a responder covering the asset kind (see Rules.assetSpecialty). */
    suspend fun setStatus(assetId: String, state: AssetState, note: String? = null) {
        db.rpc("set_asset_status", buildJsonObject {
            put("p_asset_id", assetId)
            put("p_state", state.wire)
            put("p_note", note?.takeIf { it.isNotBlank() })
        })
    }

    /** v_compliance: every scheduled check with overdue / due_soon / ok, soonest first. */
    suspend fun compliance(): List<ComplianceItem> = db.call {
        from("v_compliance").select { order("due_at", Order.ASCENDING) }.decodeList<ComplianceItem>()
    }

    fun listenCompliance(): Flow<Loadable<List<ComplianceItem>>> = live.live("compliance", table("asset_checks")) { compliance() }

    /**
     * Marks a check done and rolls due_at forward. Admin or a responder covering it.
     * Returns the next due date ("Done. Next due {date}").
     */
    suspend fun logCheck(checkId: String, notes: String? = null): LocalDate? {
        val row: JsonObject = db.rpcAs("log_asset_check", buildJsonObject {
            put("p_check_id", checkId)
            put("p_notes", notes?.takeIf { it.isNotBlank() })
        })
        return row["due_at"]?.jsonPrimitive?.contentOrNull?.let { runCatching { LocalDate.parse(it.take(10)) }.getOrNull() }
    }
}
