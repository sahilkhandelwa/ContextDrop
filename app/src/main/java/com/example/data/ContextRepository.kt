package com.example.data

import kotlinx.coroutines.flow.Flow

class ContextRepository(private val contextDao: ContextDao) {
    val allBlocks: Flow<List<ContextBlock>> = contextDao.getAllContextBlocks()
    val allHistories: Flow<List<ChatHistory>> = contextDao.getAllChatHistories()

    suspend fun getBlocksCount(): Int = contextDao.getBlocksCount()
    suspend fun getHistoriesCount(): Int = contextDao.getHistoriesCount()

    suspend fun insertBlock(block: ContextBlock) {
        contextDao.insertContextBlock(block)
    }

    suspend fun deleteBlock(block: ContextBlock) {
        contextDao.deleteContextBlock(block)
    }

    suspend fun deleteBlockById(id: Int) {
        contextDao.deleteContextBlockById(id)
    }

    suspend fun insertHistory(history: ChatHistory) {
        contextDao.insertChatHistory(history)
    }

    suspend fun deleteHistory(history: ChatHistory) {
        contextDao.deleteChatHistory(history)
    }

    suspend fun deleteHistoryById(id: Int) {
        contextDao.deleteChatHistoryById(id)
    }
}
