package com.lazydevs.wristotle.mcp

import android.content.Context
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/**
 * `streamable = true` → Streaming HTTP (current MCP spec recommendation);
 * `false` → SSE (legacy, kept because plenty of servers only speak SSE).
 * `authHeader` is the raw `Authorization` value (e.g. `"Bearer sk-..."`),
 * stored verbatim because every MCP server's auth scheme differs.
 */
@Entity(tableName = "mcp_servers")
data class McpServerEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val url: String,
    val streamable: Boolean = true,
    @ColumnInfo(name = "auth_header") val authHeader: String? = null,
    val enabled: Boolean = true,
)

@Dao
interface McpServerDao {
    @Query("SELECT * FROM mcp_servers ORDER BY id ASC")
    fun observeAll(): Flow<List<McpServerEntity>>

    @Query("SELECT * FROM mcp_servers WHERE enabled = 1 ORDER BY id ASC")
    suspend fun listEnabled(): List<McpServerEntity>

    /** Snapshot of every row including disabled ones — used by Backup. */
    @Query("SELECT * FROM mcp_servers ORDER BY id ASC")
    suspend fun listAll(): List<McpServerEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(entity: McpServerEntity): Long

    @Update
    suspend fun update(entity: McpServerEntity)

    @Query("DELETE FROM mcp_servers WHERE id = :id")
    suspend fun deleteById(id: Long)
}

@Database(entities = [McpServerEntity::class], version = 1, exportSchema = false)
abstract class McpDatabase : RoomDatabase() {
    abstract fun mcpServerDao(): McpServerDao

    companion object {
        private const val DB_NAME = "wristotle-mcp.db"

        fun build(context: Context): McpDatabase =
            Room.databaseBuilder(context.applicationContext, McpDatabase::class.java, DB_NAME)
                .build()
    }
}
