package app.kavach.android.core.data.repo

// Column lists, copied from the website so both apps read the same shape.

internal const val INCIDENT_COLUMNS =
    "id, client_id, source, type, status, reporter_id, reporter_name, reporter_phone, reporter_vulnerability, vulnerable, " +
        "zone_id, zone_name, flat_id, flat_label, floor, asset_id, asset_name, location_note, description, people_affected, " +
        "hazard_weight, zone_tier, urgency_score, escalation_level, assigned_to, assigned_name, assigned_phone, assigned_at, " +
        "acknowledged_at, en_route_at, on_scene_at, resolved_at, cancelled_at, resolution_note, safety_check_id, created_at, updated_at"

internal const val PROFILE_COLUMNS =
    "id, society_id, full_name, email, phone, role, specialties, on_duty, flat_id, language, vulnerability, first_responder_skill, " +
        "flat:flats(id, number, floor, zone_id, zone:zones(id, name, code)), society:societies(id, name, city, security_phone, power_source)"

internal const val MEMBER_COLUMNS =
    "id, full_name, email, phone, role, specialties, on_duty, flat_id, first_responder_skill, vulnerability, updated_at, " +
        "flat:flats(number, floor, zone:zones(name))"

internal const val ZONE_COLUMNS = "id, name, code, kind, tier, power_state, floors, sort_order"

internal const val SOCIETY_COLUMNS =
    "id, name, city, address, security_phone, power_source, escalate_l2_after, escalate_l3_after"

internal const val ASSET_COLUMNS =
    "id, zone_id, kind, name, code, state, vendor, amc_expires_on, licence_expires_on, notes, state_changed_at, " +
        "changed_by:profiles!assets_state_changed_by_fkey(full_name), zone:zones(id, name, sort_order)"

internal const val EVENT_COLUMNS = "id, actor_id, actor_name, action, from_status, to_status, note, data, created_at"

internal const val NOTICE_COLUMNS = "id, title, body, pinned, expires_at, author_name, created_at"

internal const val CONTACT_COLUMNS = "id, kind, name, role_label, phone, available, sort_order"

internal const val SAFETY_CHECK_COLUMNS = "id, scope, zone_id, incident_type, message, started_at, ended_at, zone:zones(id, name)"

internal const val POWER_EVENT_COLUMNS = "id, source, switched_at, note, by:profiles(full_name)"
