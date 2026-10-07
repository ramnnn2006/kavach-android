package app.kavach.android.core.data.outbox

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

/** One queued report. [payloadJson] is a serialized NewIncident. [state] is "pending" or "failed". */
@Entity(tableName = "outbox_reports")
data class OutboxEntity(
    @PrimaryKey val clientId: String,
    val userId: String?,
    val payloadJson: String,
    val createdAt: Long,
    val attempts: Int,
    val lastErrorCode: String?,
    val lastErrorMessage: String?,
    val state: String,
)

@Dao
interface OutboxDao {
    @Query("SELECT * FROM outbox_reports ORDER BY createdAt")
    fun observeAll(): Flow<List<OutboxEntity>>

    @Query("SELECT * FROM outbox_reports ORDER BY createdAt")
    suspend fun all(): List<OutboxEntity>

    @Query("SELECT * FROM outbox_reports WHERE clientId = :clientId")
    suspend fun get(clientId: String): OutboxEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(entity: OutboxEntity)

    /** Number of rows removed (0 when it was already gone). */
    @Query("DELETE FROM outbox_reports WHERE clientId = :clientId")
    suspend fun delete(clientId: String): Int

    @Query("DELETE FROM outbox_reports")
    suspend fun clear()
}

@Database(entities = [OutboxEntity::class], version = 1, exportSchema = false)
abstract class OutboxDatabase : RoomDatabase() {
    abstract fun outboxDao(): OutboxDao
}
