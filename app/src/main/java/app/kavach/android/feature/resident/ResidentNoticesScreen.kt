package app.kavach.android.feature.resident

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Campaign
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.kavach.android.R
import app.kavach.android.core.data.model.Notice
import app.kavach.android.core.data.remote.Loadable
import app.kavach.android.core.data.repo.NoticeRepository
import app.kavach.android.core.nav.KavachNavigator
import app.kavach.android.core.ui.components.EmptyState
import app.kavach.android.core.ui.components.KavachCard
import app.kavach.android.core.ui.components.KavachScaffold
import app.kavach.android.core.ui.components.LoadableContent
import app.kavach.android.core.ui.components.SectionHeader
import app.kavach.android.core.ui.format.Format
import app.kavach.android.core.ui.theme.KavachTheme
import app.kavach.android.core.ui.theme.Spacing
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import java.time.Instant
import javax.inject.Inject

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ResidentNoticesViewModel @Inject constructor(repo: NoticeRepository) : ViewModel() {
    private val tick = MutableStateFlow(0)
    fun retry() { tick.value += 1 }

    /** Ticks once a minute while the screen is shown, so a notice disappears when it expires. */
    private val clock = flow {
        while (true) {
            emit(Instant.now())
            delay(60_000)
        }
    }

    val notices: StateFlow<Loadable<List<Notice>>> = combine(
        tick.flatMapLatest { repo.listenNotices() }.keepLoadedData(),
        clock,
    ) { state, now ->
        state.data?.let { state.copy(data = ResidentRules.visibleNotices(it, now)) } ?: state
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), Loadable<List<Notice>>())
}

/** Society notices from the committee, pinned first. */
@Composable
fun ResidentNoticesScreen(navigator: KavachNavigator) {
    val vm: ResidentNoticesViewModel = hiltViewModel()
    val state by vm.notices.collectAsStateWithLifecycle()

    KavachScaffold(
        title = stringResource(R.string.resident_notices_title),
        subtitle = stringResource(R.string.resident_notices_sub),
    ) { padding ->
        LoadableContent(state, vm::retry, Modifier.fillMaxSize().padding(padding)) { data ->
            if (data.isEmpty()) {
                EmptyState(
                    icon = Icons.Rounded.Campaign,
                    title = stringResource(R.string.resident_no_notices_title),
                    text = stringResource(R.string.resident_no_notices_text),
                )
            } else {
                val (pinned, rest) = remember(data) { ResidentRules.splitNotices(data) }
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = Spacing.screen, end = Spacing.screen, top = Spacing.sm, bottom = Spacing.sm + app.kavach.android.core.ui.components.LocalFloatingBarInset.current),
                    verticalArrangement = Arrangement.spacedBy(Spacing.sm),
                ) {
                    if (pinned.isNotEmpty()) {
                        item(key = "pinned-title") { SectionHeader(stringResource(R.string.resident_pinned)) }
                        items(pinned, key = { it.id }, contentType = { "notice" }) { notice -> NoticeCard(notice, reorderModifier()) }
                    }
                    if (rest.isNotEmpty()) {
                        item(key = "rest-title") {
                            SectionHeader(
                                stringResource(if (pinned.isNotEmpty()) R.string.resident_notices_recent else R.string.resident_notices_all),
                            )
                        }
                        items(rest, key = { it.id }, contentType = { "notice" }) { notice -> NoticeCard(notice, reorderModifier()) }
                    }
                }
            }
        }
    }
}

@Composable
private fun NoticeCard(notice: Notice, modifier: Modifier = Modifier) {
    KavachCard(modifier = modifier) {
        Text(notice.title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
        if (notice.body.isNotBlank()) {
            Text(notice.body, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = Spacing.xs))
        }
        Text(
            listOfNotNull(notice.authorName?.takeIf { it.isNotBlank() }, Format.dateTime(notice.createdAt)).joinToString(" · "),
            style = MaterialTheme.typography.bodySmall,
            color = KavachTheme.colors.textMuted,
            modifier = Modifier.padding(top = Spacing.sm),
        )
    }
}
