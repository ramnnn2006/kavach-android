package app.kavach.android.feature.shared

import android.content.Context
import app.kavach.android.R
import app.kavach.android.core.data.model.Incident
import app.kavach.android.core.data.model.IncidentEvent
import app.kavach.android.core.data.model.parseInstant
import app.kavach.android.core.domain.Phones
import app.kavach.android.core.ui.components.eventText
import app.kavach.android.core.ui.format.Format
import app.kavach.android.core.ui.format.labelRes
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Plain-text version of the printable incident report (the incident_report RPC result:
 * `incident`, `events`, `first_responders`, `generated_at`) for the Android share sheet.
 */
internal object IncidentReportText {
    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        explicitNulls = false
    }

    /** Returns null when the report has no readable incident. */
    fun build(context: Context, report: JsonObject): String? {
        val incident = report["incident"]?.let {
            runCatching { json.decodeFromJsonElement(Incident.serializer(), it) }.getOrNull()
        } ?: return null
        val events = (report["events"] as? JsonArray).orEmpty().mapNotNull {
            runCatching { json.decodeFromJsonElement(IncidentEvent.serializer(), it) }.getOrNull()
        }
        val responders = (report["first_responders"] as? JsonArray)?.size ?: 0
        val generated = (report["generated_at"] as? JsonPrimitive)?.contentOrNull
            ?.let { runCatching { parseInstant(it) }.getOrNull() }

        val lines = mutableListOf<String>()
        fun line(@androidx.annotation.StringRes label: Int, value: String?) {
            if (!value.isNullOrBlank()) {
                lines += context.getString(R.string.shared_report_line, context.getString(label), value)
            }
        }

        lines += context.getString(R.string.shared_report_title)
        lines += ""
        line(R.string.shared_report_type, context.getString(incident.type.labelRes()))
        line(R.string.shared_report_status, context.getString(incident.status.labelRes(true)))
        line(R.string.shared_report_place, Format.place(context, incident))
        line(R.string.shared_report_reported, Format.dateTime(incident.createdAt))
        line(R.string.shared_detail_reported_by, withPhone(context, incident.reporterName, incident.reporterPhone))
        line(R.string.shared_detail_needs, Format.vulnerabilityFull(context, incident.reporterVulnerability))
        line(R.string.shared_detail_people_affected, Format.people(context, incident.peopleAffected))
        line(R.string.shared_detail_description, incident.description)
        line(R.string.shared_detail_location_note, incident.locationNote)
        line(R.string.shared_detail_urgency, context.getString(R.string.shared_detail_urgency_value, incident.urgencyScore))
        if (incident.isEscalated) line(R.string.shared_report_escalation, incident.escalationLevel.toString())
        line(R.string.shared_detail_assigned_to, withPhone(context, incident.assignedName, incident.assignedPhone))
        line(R.string.shared_detail_resolution_note, incident.resolutionNote)
        if (responders > 0) line(R.string.shared_report_first_responders, responders.toString())

        if (events.isNotEmpty()) {
            lines += ""
            lines += context.getString(R.string.shared_report_events)
            for (event in events) {
                lines += context.getString(
                    R.string.shared_report_event_line,
                    Format.dateTime(event.createdAt),
                    eventText(context, event, staff = true),
                )
                if (!event.note.isNullOrBlank()) lines += "    " + context.getString(R.string.shared_report_event_note, event.note)
            }
        }

        if (generated != null) {
            lines += ""
            lines += context.getString(R.string.shared_report_generated, Format.dateTime(generated))
        }
        return lines.joinToString("\n")
    }

    private fun withPhone(context: Context, name: String?, phone: String?): String? {
        val who = name?.takeIf { it.isNotBlank() }
        val number = phone?.takeIf { it.isNotBlank() }?.let { Phones.display(it) }
        return when {
            who != null && number != null -> context.getString(R.string.shared_report_phone, who, number)
            who != null -> who
            else -> number
        }
    }
}
