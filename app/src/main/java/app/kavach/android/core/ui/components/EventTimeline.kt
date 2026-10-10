package app.kavach.android.core.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.kavach.android.R
import app.kavach.android.core.data.model.EventAction
import app.kavach.android.core.data.model.IncidentEvent
import app.kavach.android.core.ui.format.Format
import app.kavach.android.core.ui.format.labelRes
import app.kavach.android.core.ui.theme.KavachTheme
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import java.time.LocalDate
import java.time.ZoneId

/** Events whose note is worth showing to the resident. */
private val NOTE_ACTIONS = setOf(EventAction.Resolved, EventAction.StatusChanged, EventAction.NoteAdded)
/** Moves the progress steps already show (resident); escalation has its own banner. */
private val STEP_ACTIONS = setOf(EventAction.Created, EventAction.Claimed, EventAction.StatusChanged, EventAction.Resolved, EventAction.Escalated)

/** resident visibleEvents(): with [hideSteps], plain status moves are left to the progress steps. */
fun visibleEvents(events: List<IncidentEvent>, hideSteps: Boolean): List<IncidentEvent> =
    if (!hideSteps) events else events.filter { it.action !in STEP_ACTIONS || (!it.note.isNullOrBlank() && it.action in NOTE_ACTIONS) }

/** One line of text for an audit event. [staff] picks the status wording. */
fun eventText(context: android.content.Context, ev: IncidentEvent, staff: Boolean): String {
    val actor = ev.actorName?.takeIf { it.isNotBlank() } ?: context.getString(R.string.common_system)
    return when (ev.action) {
        EventAction.Claimed -> context.getString(R.string.common_event_claimed, actor)
        EventAction.Assigned, EventAction.Reassigned -> {
            // `as?`: a non-primitive value (object/array) must not crash the timeline
            val to = (ev.data["assigned_name"] as? JsonPrimitive)?.contentOrNull
                ?: (ev.data["to"] as? JsonPrimitive)?.contentOrNull ?: actor
            context.getString(ev.action.labelRes(), to)
        }
        EventAction.StatusChanged -> {
            val status = ev.toStatus?.let { context.getString(it.labelRes(staff)) } ?: ""
            context.getString(R.string.common_event_status_changed, status)
        }
        EventAction.Escalated -> {
            val level = (ev.data["level"] as? JsonPrimitive)?.intOrNull ?: 2
            context.getString(R.string.common_event_escalated, level)
        }
        else -> context.getString(ev.action.labelRes())
    }
}

/** Audit trail: time, what happened, who, and the note. */
@Composable
fun EventTimeline(events: List<IncidentEvent>, staff: Boolean, modifier: Modifier = Modifier, hideSteps: Boolean = false) {
    val context = LocalContext.current
    val shown = remember(events, hideSteps) { visibleEvents(events, hideSteps) }
    val zone = ZoneId.systemDefault()
    val today = LocalDate.now(zone)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        shown.forEach { ev ->
            // One TalkBack stop per event (time, text, who, note)
            Row(
                Modifier.semantics(mergeDescendants = true) {},
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // Events from another day carry their date (website: dateTime on older events)
                val time = if (ev.createdAt.atZone(zone).toLocalDate() == today) Format.clock(ev.createdAt) else Format.dateTime(ev.createdAt)
                Text(time, style = MaterialTheme.typography.bodySmall, color = KavachTheme.colors.textMuted, modifier = Modifier.padding(top = 2.dp))
                Column(Modifier.weight(1f)) {
                    Text(eventText(context, ev, staff), style = MaterialTheme.typography.bodyMedium)
                    val showActor = staff && ev.action != EventAction.Claimed && !ev.actorName.isNullOrBlank()
                    if (showActor) Text(context.getString(R.string.common_event_by, ev.actorName), style = MaterialTheme.typography.bodySmall, color = KavachTheme.colors.textMuted)
                    // Residents see notes only on resolve / status / note events (resident Timeline.jsx); staff see all
                    val showNote = !ev.note.isNullOrBlank() && (staff || ev.action in NOTE_ACTIONS)
                    if (showNote) Text("“${ev.note}”", style = MaterialTheme.typography.bodyMedium, color = KavachTheme.colors.textMuted)
                }
            }
        }
    }
}
