package app.kavach.android.feature.responder

import androidx.lifecycle.viewModelScope
import app.kavach.android.R
import app.kavach.android.core.data.model.Asset
import app.kavach.android.core.data.model.AssetState
import app.kavach.android.core.data.model.Profile
import app.kavach.android.core.data.remote.Loadable
import app.kavach.android.core.data.remote.messageRes
import app.kavach.android.core.data.repo.AssetRepository
import app.kavach.android.core.data.session.SessionManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Everything the Assets screen draws. */
data class AssetsUi(
    val assets: Loadable<AssetsData> = Loadable(),
    val profile: Profile? = null,
)

@HiltViewModel
class ResponderAssetsViewModel @Inject constructor(
    private val assetRepo: AssetRepository,
    session: SessionManager,
) : ActionViewModel() {

    private val retryCount = MutableStateFlow(0)
    private val _edit = MutableStateFlow(DialogUi<String>())

    /** The status dialog: target is the asset id (the screen looks the live row up). */
    val edit: StateFlow<DialogUi<String>> = _edit.asStateFlow()

    @OptIn(ExperimentalCoroutinesApi::class)
    private val rows: Flow<Loadable<List<Asset>>> = retryCount
        .flatMapLatest { assetRepo.listenAssets() }
        .keepLastData()

    val ui: StateFlow<AssetsUi> = combine(rows, session.profile) { loaded, profile ->
        AssetsUi(
            assets = Loadable(
                data = loaded.data?.let { assetsData(it, profile) },
                error = loaded.error,
                loading = loaded.loading,
            ),
            profile = profile,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AssetsUi())

    fun retry() { retryCount.update { it + 1 } }

    fun openEdit(assetId: String) { _edit.value = DialogUi(target = assetId) }

    fun dismissEdit() {
        if (!_edit.value.busy) _edit.value = DialogUi()
    }

    /**
     * Save a new [state] for [asset] with an optional [note]. [stateLabel] is the state's display
     * text for the confirmation. Nothing changed and no note: just closes (AssetDialog.jsx).
     */
    fun save(asset: Asset, state: AssetState, note: String, stateLabel: String) {
        val current = _edit.value
        if (current.busy) return
        if (state == asset.state && note.isBlank()) {
            _edit.value = DialogUi()
            return
        }
        _edit.value = current.copy(busy = true, error = null)
        viewModelScope.launch {
            val saved = attempt(onError = { e -> _edit.update { it.copy(busy = false, error = e.messageRes()) } }) {
                assetRepo.setStatus(asset.id, state, note.ifBlank { null })
            }
            if (saved != null) {
                _edit.value = DialogUi()
                confirm()
                messages.send(R.string.responder_asset_saved_toast, asset.name, stateLabel)
            }
        }
    }
}
