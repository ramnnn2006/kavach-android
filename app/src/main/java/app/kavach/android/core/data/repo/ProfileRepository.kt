package app.kavach.android.core.data.repo

import app.kavach.android.core.data.model.FirstResponderSkill
import app.kavach.android.core.data.model.LanguageCode
import app.kavach.android.core.data.model.Profile
import app.kavach.android.core.data.model.Vulnerability
import app.kavach.android.core.data.remote.Db
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.query.Columns
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put
import javax.inject.Inject
import javax.inject.Singleton

/** Fields a user may change on their own profile (db.js SELF_EDITABLE). Set only what changes. */
class ProfilePatch {
    internal val fields = linkedMapOf<String, JsonElement>()
    internal var vulnerabilityValue: Vulnerability? = null

    fun fullName(value: String) = apply { fields["full_name"] = JsonPrimitive(value.trim()) }
    fun phone(value: String?) = apply { fields["phone"] = value?.trim()?.ifBlank { null }?.let(::JsonPrimitive) ?: JsonNull }
    fun language(value: LanguageCode) = apply { fields["language"] = JsonPrimitive(value.wire) }
    fun vulnerability(value: Vulnerability) = apply { vulnerabilityValue = value }
    /** null clears it (opts out of medical alerts). */
    fun firstResponderSkill(value: FirstResponderSkill?) =
        apply { fields["first_responder_skill"] = value?.let { JsonPrimitive(it.wire) } ?: JsonNull }

    val isEmpty: Boolean get() = fields.isEmpty() && vulnerabilityValue == null
}

@Singleton
class ProfileRepository @Inject constructor(
    private val db: Db,
    private val json: Json,
) {
    suspend fun fetch(userId: String): Profile? = db.call {
        from("profiles").select(Columns.raw(PROFILE_COLUMNS)) { filter { eq("id", userId) } }
            .decodeSingleOrNull<Profile>()
    }

    suspend fun updateMine(patch: ProfilePatch) {
        if (patch.isEmpty) return
        val me = db.myId()
        val body = JsonObject(buildMap {
            putAll(patch.fields)
            patch.vulnerabilityValue?.let { put("vulnerability", json.encodeToJsonElement(it)) }
        })
        db.call { from("profiles").update(body) { filter { eq("id", me) } } }
    }

    /** Responder / admin duty switch. */
    suspend fun setOnDuty(on: Boolean) {
        db.rpc("set_on_duty", buildJsonObject { put("p_on", on) })
    }

    /** Resident picks tower + flat (Welcome screen fallback for accounts without a flat). */
    suspend fun setMyHome(zoneId: String, floor: Int?, flatNumber: String) {
        db.rpc("set_my_home", buildJsonObject {
            put("p_zone_id", zoneId)
            put("p_floor", floor)
            put("p_flat_number", flatNumber.trim())
        })
    }
}
