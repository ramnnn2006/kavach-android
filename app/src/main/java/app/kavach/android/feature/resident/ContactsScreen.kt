package app.kavach.android.feature.resident

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.kavach.android.R
import app.kavach.android.core.config.AppConfig
import app.kavach.android.core.data.model.Contact
import app.kavach.android.core.data.remote.Loadable
import app.kavach.android.core.data.repo.ContactRepository
import app.kavach.android.core.nav.KavachNavigator
import app.kavach.android.core.ui.components.ErrorBanner
import app.kavach.android.core.ui.components.KavachCard
import app.kavach.android.core.ui.components.KavachScaffold
import app.kavach.android.core.ui.components.SectionHeader
import app.kavach.android.core.ui.theme.KavachTheme
import app.kavach.android.core.ui.theme.Spacing
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ContactsViewModel @Inject constructor(repo: ContactRepository) : ViewModel() {
    private val tick = MutableStateFlow(0)
    fun retry() { tick.value += 1 }

    val contacts: StateFlow<Loadable<List<Contact>>> = tick
        .flatMapLatest { repo.listenContacts() }
        .keepLoadedData()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), Loadable<List<Contact>>())
}

/** Public numbers every society shows when it has not added its own emergency contacts. */
private data class PublicNumber(val number: String, val labelRes: Int)

private val PUBLIC_EMERGENCY = listOf(
    PublicNumber(AppConfig.EMERGENCY_112, R.string.resident_contact_112),
    PublicNumber(AppConfig.FIRE_101, R.string.resident_contact_101),
    PublicNumber(AppConfig.AMBULANCE_108, R.string.resident_contact_108),
)

/** Society contacts and public emergency numbers; tap a row to call. */
@Composable
fun ContactsScreen(navigator: KavachNavigator) {
    val vm: ContactsViewModel = hiltViewModel()
    val state by vm.contacts.collectAsStateWithLifecycle()
    val data = state.data
    val error = state.error
    val (society, emergencyRows) = remember(data) { ResidentRules.groupContacts(data.orEmpty()) }

    KavachScaffold(
        title = stringResource(R.string.resident_contacts_title),
        subtitle = stringResource(R.string.resident_contacts_sub),
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = Spacing.screen, vertical = Spacing.sm),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            if (error != null) ErrorBanner(error, vm::retry)

            Column {
                SectionHeader(stringResource(R.string.resident_group_society))
                when {
                    data == null && error == null -> KavachCard {
                        val loading = stringResource(R.string.common_loading)
                        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(
                                Modifier.size(24.dp).semantics { contentDescription = loading },
                                strokeWidth = 2.dp,
                            )
                        }
                    }
                    society.isEmpty() -> KavachCard {
                        Text(
                            stringResource(R.string.resident_no_society_contacts),
                            style = MaterialTheme.typography.bodyMedium,
                            color = KavachTheme.colors.textMuted,
                        )
                    }
                    else -> KavachCard(contentPadding = PaddingValues(0.dp)) {
                        society.forEachIndexed { index, contact ->
                            if (index > 0) HorizontalDivider(color = KavachTheme.colors.separator)
                            ContactRow(contact)
                        }
                    }
                }
            }

            Column {
                SectionHeader(stringResource(R.string.resident_group_emergency))
                KavachCard(contentPadding = PaddingValues(0.dp)) {
                    if (emergencyRows.isNotEmpty()) {
                        emergencyRows.forEachIndexed { index, contact ->
                            if (index > 0) HorizontalDivider(color = KavachTheme.colors.separator)
                            ContactRow(contact)
                        }
                    } else {
                        val allDay = stringResource(R.string.resident_all_day)
                        PUBLIC_EMERGENCY.forEachIndexed { index, entry ->
                            if (index > 0) HorizontalDivider(color = KavachTheme.colors.separator)
                            val name = stringResource(entry.labelRes)
                            CallRow(
                                phone = entry.number,
                                title = name,
                                detail = allDay,
                                number = entry.number,
                                callLabel = stringResource(R.string.common_call_number, name),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ContactRow(contact: Contact) {
    CallRow(
        phone = contact.phone,
        title = contact.name,
        detail = ResidentRules.contactDetail(contact),
        number = contact.phone,
        callLabel = stringResource(R.string.common_call_number, contact.name),
    )
}
