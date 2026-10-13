package app.kavach.android.core.di

import android.content.Context
import androidx.room.Room
import app.kavach.android.BuildConfig
import app.kavach.android.core.data.outbox.OutboxDao
import app.kavach.android.core.data.outbox.OutboxDatabase
import app.kavach.android.core.data.session.EncryptedSessionManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.createSupabaseClient
import io.ktor.client.engine.okhttp.OkHttp
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.realtime.Realtime
import io.github.jan.supabase.serializer.KotlinXSerializer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.serialization.json.Json
import kotlin.time.Duration.Companion.seconds
import javax.inject.Qualifier
import javax.inject.Singleton

/** App-lifetime scope for work that must outlive a screen (cleanup, outbox, alerts). */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ApplicationScope

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideJson(): Json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        explicitNulls = false
        encodeDefaults = true
    }

    @Provides
    @Singleton
    @ApplicationScope
    fun provideAppScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Provides
    @Singleton
    fun provideSupabase(@ApplicationContext context: Context, json: Json): SupabaseClient =
        createSupabaseClient(
            supabaseUrl = BuildConfig.SUPABASE_URL,
            supabaseKey = BuildConfig.SUPABASE_KEY,
        ) {
            defaultSerializer = KotlinXSerializer(json)
            // Set the engine directly instead of ServiceLoader discovery (safer under R8)
            httpEngine = OkHttp.create()
            // The library default (10 s) aborts a report before the 15 s send rule
            // (AppConfig.SEND_TIMEOUT_MS) can queue it and catch a late success; slow mobile
            // networks also need longer for lists
            requestTimeout = 30.seconds
            install(Auth) {
                sessionManager = EncryptedSessionManager(context, json)
                autoLoadFromStorage = true
                alwaysAutoRefresh = true
            }
            install(Postgrest)
            install(Realtime) {
                // Same as supabase-js; fewer wake-ups while the alert service holds the socket
                heartbeatInterval = 25.seconds
            }
        }

    @Provides
    @Singleton
    fun provideOutboxDatabase(@ApplicationContext context: Context): OutboxDatabase =
        Room.databaseBuilder(context, OutboxDatabase::class.java, "kavach_outbox.db").build()

    @Provides
    fun provideOutboxDao(db: OutboxDatabase): OutboxDao = db.outboxDao()
}
