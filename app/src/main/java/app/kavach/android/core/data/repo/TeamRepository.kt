package app.kavach.android.core.data.repo

import app.kavach.android.core.data.model.ApprovedMember
import app.kavach.android.core.data.model.IncidentType
import app.kavach.android.core.data.model.Member
import app.kavach.android.core.data.model.TeamMember
import app.kavach.android.core.data.model.UserRole
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
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import javax.inject.Inject
import javax.inject.Singleton

/** People: existing accounts (profiles, v_team) and the admin-approved phone list (members). */
@Singleton
class TeamRepository @Inject constructor(
    private val db: Db,
    private val live: LiveQueries,
) {
    /** Accounts in my society (RLS), by role then name. */
    suspend fun members(role: UserRole? = null): List<Member> = db.call {
        from("profiles").select(Columns.raw(MEMBER_COLUMNS)) {
            filter { role?.let { eq("role", it.wire) } }
            order("role", Order.ASCENDING)
            order("full_name", Order.ASCENDING)
        }.decodeList<Member>()
    }

    fun listenMembers(role: UserRole? = null): Flow<Loadable<List<Member>>> =
        live.live("members:$role", table("profiles")) { members(role) }

    /** v_team: responders + admins with active load (admins first, then by name). */
    suspend fun team(): List<TeamMember> = db.call {
        from("v_team").select {
            order("role", Order.DESCENDING)
            order("full_name", Order.ASCENDING)
        }.decodeList<TeamMember>()
    }

    fun listenTeam(): Flow<Loadable<List<TeamMember>>> =
        live.live("team", table("profiles"), table("incidents")) { team() }

    /** Admin: change an account's role / specialties. Can't demote yourself. */
    suspend fun updateMember(profileId: String, role: UserRole, specialties: List<IncidentType>?) {
        db.rpc("update_member", buildJsonObject {
            put("p_profile_id", profileId)
            put("p_role", role.wire)
            put("p_specialties", specialties?.let { list -> JsonArray(list.map { JsonPrimitive(it.wire) }) } ?: JsonNull)
        })
    }

    // ── Approved phone list (members table) ─────────────────────────────────
    suspend fun approvedMembers(): List<ApprovedMember> =
        db.rpcAs("list_approved_members")

    /** The list has no columns from profiles; members rows change when someone signs in (db.js). */
    fun listenApprovedMembers(): Flow<Loadable<List<ApprovedMember>>> =
        live.live("approved-members", table("members")) { approvedMembers() }

    /**
     * Add a number. [phone] in E.164 (+91…). Residents need [zoneId] + [flatNumber]; staff may
     * leave them null. Errors: invalid ("Enter a valid phone number", "This phone number is
     * already on the list", "Residents need a flat", "Name is required", flat errors), forbidden.
     * The RPC returns a one-row table shaped like list_approved_members().
     */
    suspend fun addApprovedMember(
        phone: String,
        fullName: String,
        role: UserRole,
        zoneId: String?,
        flatNumber: String?,
        specialties: List<IncidentType>,
    ): ApprovedMember = db.rpcAs<List<ApprovedMember>>("add_approved_member", buildJsonObject {
        put("p_phone", phone.trim())
        put("p_full_name", fullName.trim())
        put("p_role", role.wire)
        put("p_zone_id", zoneId)
        put("p_flat_number", flatNumber?.trim()?.ifBlank { null })
        put("p_specialties", JsonArray(specialties.map { JsonPrimitive(it.wire) }))
    }).firstOrNull() ?: throw KavachException(ErrorCode.NotFound)

    /** Same rules as add; if the person has already signed in, their profile is updated too. */
    suspend fun updateApprovedMember(
        id: String,
        phone: String,
        fullName: String,
        role: UserRole,
        zoneId: String?,
        flatNumber: String?,
        specialties: List<IncidentType>,
    ): ApprovedMember = db.rpcAs<List<ApprovedMember>>("update_approved_member", buildJsonObject {
        put("p_member_id", id)
        put("p_phone", phone.trim())
        put("p_full_name", fullName.trim())
        put("p_role", role.wire)
        put("p_zone_id", zoneId)
        put("p_flat_number", flatNumber?.trim()?.ifBlank { null })
        put("p_specialties", JsonArray(specialties.map { JsonPrimitive(it.wire) }))
    }).firstOrNull() ?: throw KavachException(ErrorCode.NotFound)

    /** Removes the number from the list. An existing account is not deleted. */
    suspend fun removeApprovedMember(id: String) {
        db.rpc("remove_approved_member", buildJsonObject { put("p_member_id", id) })
    }
}
