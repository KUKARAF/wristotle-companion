package com.lazydevs.wristotle.mcp

import kotlinx.coroutines.flow.Flow

/**
 * Thin CRUD wrapper around [McpServerDao]. Lives mostly to keep the
 * ViewModel from touching Room types directly and to give the rest of
 * the app a stable surface if we ever swap the storage backend (e.g.
 * for a JSON file in `filesDir` to make backups simpler).
 */
class McpServerRepository(private val dao: McpServerDao) {

    fun observeServers(): Flow<List<McpServerEntity>> = dao.observeAll()

    suspend fun listEnabled(): List<McpServerEntity> = dao.listEnabled()

    suspend fun add(server: McpServerEntity): Long = dao.insert(server)

    suspend fun update(server: McpServerEntity) = dao.update(server)

    suspend fun delete(id: Long) = dao.deleteById(id)
}
