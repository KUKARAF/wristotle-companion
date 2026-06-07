// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2025-2026 Lazy Devs

package com.lazydevs.wristotle.mcp

import kotlinx.coroutines.flow.Flow

class McpServerRepository(private val dao: McpServerDao) {
    fun observeServers(): Flow<List<McpServerEntity>> = dao.observeAll()
    suspend fun listEnabled(): List<McpServerEntity> = dao.listEnabled()
    suspend fun listAll(): List<McpServerEntity> = dao.listAll()
    suspend fun count(): Int = dao.count()
    suspend fun add(server: McpServerEntity): Long = dao.insert(server)
    suspend fun update(server: McpServerEntity) = dao.update(server)
    suspend fun delete(id: Long) = dao.deleteById(id)
}