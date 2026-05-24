package com.example.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "context_blocks")
data class ContextBlock(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val title: String,
    val content: String,
    val model: String, // "Claude", "ChatGPT", "Gemini", "General"
    val category: String, // "System", "Code", "Instruction", "Persona"
    val tags: String, // Comma-separated tags
    val timestamp: Long = System.currentTimeMillis()
)

@Entity(tableName = "chat_histories")
data class ChatHistory(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val title: String,
    val rawTranscript: String,
    val model: String, // "Claude", "ChatGPT", "Gemini", "General"
    val tags: String, // Comma-separated tags
    val timestamp: Long = System.currentTimeMillis()
)

@Dao
interface ContextDao {
    // Context Blocks
    @Query("SELECT COUNT(*) FROM context_blocks")
    suspend fun getBlocksCount(): Int

    @Query("SELECT * FROM context_blocks ORDER BY timestamp DESC")
    fun getAllContextBlocks(): Flow<List<ContextBlock>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertContextBlock(block: ContextBlock)

    @Delete
    suspend fun deleteContextBlock(block: ContextBlock)

    @Query("DELETE FROM context_blocks WHERE id = :id")
    suspend fun deleteContextBlockById(id: Int)

    // Chat Histories
    @Query("SELECT COUNT(*) FROM chat_histories")
    suspend fun getHistoriesCount(): Int

    @Query("SELECT * FROM chat_histories ORDER BY timestamp DESC")
    fun getAllChatHistories(): Flow<List<ChatHistory>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertChatHistory(history: ChatHistory)

    @Delete
    suspend fun deleteChatHistory(history: ChatHistory)

    @Query("DELETE FROM chat_histories WHERE id = :id")
    suspend fun deleteChatHistoryById(id: Int)
}

@Database(entities = [ContextBlock::class, ChatHistory::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun contextDao(): ContextDao
}
