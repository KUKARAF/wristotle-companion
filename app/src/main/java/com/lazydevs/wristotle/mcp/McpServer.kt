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
 * One MCP server configured by the user (HTTP endpoint + optional
 * `Authorization` header). Phase A only persists the inputs the user
 * types — no prompts, no per-server tool overrides, no metadata.
 *
 * `streamable` chooses between MCP's two HTTP transports: `true` =
 * Streaming HTTP (the current recommendation in the MCP spec), `false`
 * = SSE (legacy, kept because plenty of servers in the wild still only
 * speak SSE). Default to streaming for new entries.
 *
 * `authHeader` is the raw value the client sends as the `Authorization`
 * header — e.g. `"Bearer sk-..."`. Null when the server takes no auth.
 * Stored verbatim because every MCP server wants a different scheme;
 * SharedPreferences-style "API key" abstractions don't fit.
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

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(entity: McpServerEntity): Long

    @Update
    suspend fun update(entity: McpServerEntity)

    @Query("DELETE FROM mcp_servers WHERE id = :id")
    suspend fun deleteById(id: Long)
}

/**
 * Separate Room DB at `wristotle-mcp.db` — same isolation pattern as
 * Notes / Tasks / NLU / AppIndex. Keeping MCP state out of the main
 * conversation DB means a corrupt MCP table can't take down the
 * conversation history, and exports/backups can choose to skip MCP
 * config (it's typically per-device-paired, not portable).
 *
 * Schema migrations: when changing the entity, bump [version] and add a
 * Migration object below; never use `.fallbackToDestructiveMigration()`
 * (the per-feature memory `feedback_portable_format_over_storage_copy`
 * applies — a user's MCP servers shouldn't get wiped on app upgrade).
 */
@Database(entities = [McpServerEntity::class], version = 1, exportSchema = false)
abstract class McpDatabase : RoomDatabase() {
    abstract fun mcpServerDao(): McpServerDao

    companion object {
        private const val DB_NAME = "wristotle-mcp.db"

        /**
         * Built once per process by `WristotleApplication`. Non-destructive
         * migrations only — user-typed server configs are user data.
         */
        fun build(context: Context): McpDatabase =
            Room.databaseBuilder(context.applicationContext, McpDatabase::class.java, DB_NAME)
                .build()
    }
}
