package app.kavach.android.core.data.session

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import io.github.jan.supabase.auth.SessionManager
import io.github.jan.supabase.auth.user.UserSession
import kotlinx.serialization.json.Json

/** SharedPreferences encrypted with a Keystore-backed key (tokens, cached profile). */
object SecurePrefs {
    private const val TAG = "SecurePrefs"

    fun open(context: Context, name: String): SharedPreferences {
        val app = context.applicationContext
        runCatching { app.deleteSharedPreferences("${name}_plain") }
        return try {
            create(app, name)
        } catch (e: Exception) {
            // Keystore keys can be lost (restore, some OEM bugs): start clean once rather than crash
            Log.w(TAG, "Secure storage reset: ${e.javaClass.simpleName}")
            runCatching { app.deleteSharedPreferences(name) }
            try {
                create(app, name)
            } catch (e2: Exception) {
                // No usable Keystore: the app must still work, e.g. to queue an SOS
                Log.w(TAG, "Secure storage unavailable: ${e2.javaClass.simpleName}")
                MemoryPreferences()
            }
        }
    }

    private fun create(context: Context, name: String): SharedPreferences {
        val key = MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        return EncryptedSharedPreferences.create(
            context, name, key,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }
}

/** supabase-kt session storage on top of [SecurePrefs] (the default stores tokens in plain text). */
class EncryptedSessionManager(context: Context, private val json: Json) : SessionManager {
    private val prefs by lazy { SecurePrefs.open(context, "kavach_auth") }

    override suspend fun saveSession(session: UserSession) {
        prefs.edit().putString(KEY, json.encodeToString(UserSession.serializer(), session)).apply()
    }

    override suspend fun loadSession(): UserSession? {
        val text = prefs.getString(KEY, null) ?: return null
        return runCatching { json.decodeFromString(UserSession.serializer(), text) }.getOrNull()
    }

    override suspend fun deleteSession() {
        prefs.edit().remove(KEY).apply()
    }

    private companion object {
        const val KEY = "session"
    }
}
