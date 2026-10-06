package app.kavach.android.core.data.prefs

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import app.kavach.android.core.data.model.UserRole
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.settings by preferencesDataStore(name = "kavach_settings")

/** Per-device settings (not synced). Per-user settings live on the profile row. */
@Singleton
class SettingsStore @Inject constructor(@ApplicationContext private val context: Context) {
    private object Keys {
        val setupDone = booleanPreferencesKey("setup_done")
        val alertsEnabled = booleanPreferencesKey("alerts_service_enabled")
        val alertSound = booleanPreferencesKey("alert_sound")
        val introSeen = stringSetPreferencesKey("intro_seen_roles")
    }

    /** First-run setup (notification permission, battery exclusion) finished or skipped. */
    val setupDone: Flow<Boolean> = context.settings.data.map { it[Keys.setupDone] ?: false }.distinctUntilChanged()

    /** Background alert service ("Kavach is active"). Default on; Profile can turn it off. */
    val alertsEnabled: Flow<Boolean> = context.settings.data.map { it[Keys.alertsEnabled] ?: true }.distinctUntilChanged()

    /** Sound for emergency alerts while the app is open (useAlertSound on the website). */
    val alertSound: Flow<Boolean> = context.settings.data.map { it[Keys.alertSound] ?: true }.distinctUntilChanged()

    /** Roles (UserRole.wire) whose first-run walkthrough was finished or skipped on this phone. */
    val introSeenRoles: Flow<Set<String>> = context.settings.data.map { it[Keys.introSeen] ?: emptySet() }.distinctUntilChanged()

    suspend fun markIntroSeen(role: UserRole) = context.settings.edit {
        it[Keys.introSeen] = (it[Keys.introSeen] ?: emptySet()) + role.wire
    }

    suspend fun setSetupDone(done: Boolean) = context.settings.edit { it[Keys.setupDone] = done }
    suspend fun setAlertsEnabled(on: Boolean) = context.settings.edit { it[Keys.alertsEnabled] = on }
    suspend fun setAlertSound(on: Boolean) = context.settings.edit { it[Keys.alertSound] = on }

    suspend fun isSetupDone(): Boolean = setupDone.first()
}
