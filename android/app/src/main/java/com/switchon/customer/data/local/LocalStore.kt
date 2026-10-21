package com.switchon.customer.data.local

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/** Something the app will send when it's back online. Only safe, replayable actions are ever queued. */
data class PendingAction(
    /** The request id sent to the server, so a replay can't create a second booking or ticket. */
    val requestId: String,
    val kind: String,
    val title: String,
    val payload: String,
    val createdAtMillis: Long,
    /** Set when the server refused it (e.g. the slot filled up); the customer needs to act. */
    val error: String? = null,
)

/** Last known server data (as the server's JSON) plus the offline queue. */
interface LocalStore {
    suspend fun repairs(): List<String>
    suspend fun replaceRepairs(json: Map<String, String>)
    suspend fun putRepair(id: String, json: String)
    suspend fun get(key: String): String?
    suspend fun put(key: String, json: String)
    suspend fun clear()

    suspend fun pending(): List<PendingAction>
    suspend fun addPending(action: PendingAction)
    suspend fun removePending(requestId: String)
    suspend fun failPending(requestId: String, error: String)
    /** Emits whenever the queue changes. */
    val pendingChanges: Flow<Int>
}

// ---- Room implementation -----------------------------------------------------------------------------------

@Entity(tableName = "repairs")
data class RepairEntity(@PrimaryKey val id: String, val json: String)

@Entity(tableName = "kv")
data class KvEntity(@PrimaryKey val key: String, val json: String)

@Entity(tableName = "pending_actions")
data class PendingEntity(
    @PrimaryKey val requestId: String,
    val kind: String,
    val title: String,
    val payload: String,
    val createdAtMillis: Long,
    val error: String?,
)

@Dao
interface CacheDao {
    @Query("SELECT json FROM repairs") suspend fun repairs(): List<String>
    @Upsert suspend fun putRepairs(rows: List<RepairEntity>)
    @Query("DELETE FROM repairs") suspend fun clearRepairs()
    @Transaction suspend fun replaceRepairs(rows: List<RepairEntity>) { clearRepairs(); putRepairs(rows) }

    @Query("SELECT json FROM kv WHERE `key` = :key") suspend fun get(key: String): String?
    @Upsert suspend fun put(row: KvEntity)
    @Query("DELETE FROM kv") suspend fun clearKv()

    @Query("SELECT * FROM pending_actions ORDER BY createdAtMillis") suspend fun pending(): List<PendingEntity>
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun addPending(row: PendingEntity)
    @Query("DELETE FROM pending_actions WHERE requestId = :id") suspend fun removePending(id: String)
    @Query("UPDATE pending_actions SET error = :error WHERE requestId = :id") suspend fun failPending(id: String, error: String)
    @Query("SELECT count(*) FROM pending_actions") fun pendingCount(): Flow<Int>
}

@Database(entities = [RepairEntity::class, KvEntity::class, PendingEntity::class], version = 1, exportSchema = false)
abstract class CacheDatabase : RoomDatabase() {
    abstract fun dao(): CacheDao

    companion object {
        fun create(context: Context) = Room.databaseBuilder(context, CacheDatabase::class.java, "switchon-cache.db")
            .fallbackToDestructiveMigration() // it's a cache: the server is the source of truth
            .build()
    }
}

class RoomLocalStore(private val dao: CacheDao) : LocalStore {
    override suspend fun repairs() = dao.repairs()
    override suspend fun replaceRepairs(json: Map<String, String>) = dao.replaceRepairs(json.map { RepairEntity(it.key, it.value) })
    override suspend fun putRepair(id: String, json: String) = dao.putRepairs(listOf(RepairEntity(id, json)))
    override suspend fun get(key: String) = dao.get(key)
    override suspend fun put(key: String, json: String) = dao.put(KvEntity(key, json))
    override suspend fun clear() { dao.clearRepairs(); dao.clearKv() }
    override suspend fun pending() = dao.pending().map { PendingAction(it.requestId, it.kind, it.title, it.payload, it.createdAtMillis, it.error) }
    override suspend fun addPending(action: PendingAction) =
        dao.addPending(PendingEntity(action.requestId, action.kind, action.title, action.payload, action.createdAtMillis, action.error))
    override suspend fun removePending(requestId: String) = dao.removePending(requestId)
    override suspend fun failPending(requestId: String, error: String) = dao.failPending(requestId, error)
    override val pendingChanges: Flow<Int> = dao.pendingCount()
}

/** In-memory store for tests. */
class MemoryLocalStore : LocalStore {
    private val repairs = linkedMapOf<String, String>()
    private val kv = mutableMapOf<String, String>()
    private val queue = MutableStateFlow<List<PendingAction>>(emptyList())
    override suspend fun repairs() = repairs.values.toList()
    override suspend fun replaceRepairs(json: Map<String, String>) { repairs.clear(); repairs.putAll(json) }
    override suspend fun putRepair(id: String, json: String) { repairs[id] = json }
    override suspend fun get(key: String) = kv[key]
    override suspend fun put(key: String, json: String) { kv[key] = json }
    override suspend fun clear() { repairs.clear(); kv.clear() }
    override suspend fun pending() = queue.value
    override suspend fun addPending(action: PendingAction) { if (queue.value.none { it.requestId == action.requestId }) queue.value = queue.value + action }
    override suspend fun removePending(requestId: String) { queue.value = queue.value.filterNot { it.requestId == requestId } }
    override suspend fun failPending(requestId: String, error: String) { queue.value = queue.value.map { if (it.requestId == requestId) it.copy(error = error) else it } }
    override val pendingChanges: Flow<Int> = queue.map { it.size }
}
