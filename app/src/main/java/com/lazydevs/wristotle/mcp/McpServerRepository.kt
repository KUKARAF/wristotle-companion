package com.lazydevs.wristotle.mcp

import kotlinx.coroutines.flow.Flow

class McpServerRepository(private val dao: McpServerDao) {
    fun observeServers(): Flow<List<McpServerEntity>> = dao.observeAll()
    suspend fun listEnabled(): List<McpServerEntity> = dao.listEnabled()
    suspend fun add(server: McpServerEntity): Long = dao.insert(server)
    suspend fun update(server: McpServerEntity) = dao.update(server)
    suspend fun delete(id: Long) = dao.deleteById(id)
}
