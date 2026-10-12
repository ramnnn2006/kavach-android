package app.kavach.android.feature.alerts

import app.kavach.android.core.data.remote.Db
import app.kavach.android.core.data.repo.IncidentRepository
import app.kavach.android.core.data.session.SessionManager
import app.kavach.android.core.di.ApplicationScope
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope

/**
 * How the broadcast receivers reach the Hilt graph. (@AndroidEntryPoint on a BroadcastReceiver
 * needs a super.onReceive() call that Kotlin rejects as an abstract call, so receivers use
 * EntryPointAccessors.fromApplication instead.)
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface AlertsEntryPoint {
    fun controller(): AlertsController
    fun incidents(): IncidentRepository
    fun notifier(): AlertNotifier
    fun session(): SessionManager
    fun store(): AlertStateStore
    fun db(): Db

    @ApplicationScope
    fun appScope(): CoroutineScope
}
