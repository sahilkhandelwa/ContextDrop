package com.example.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import com.example.data.AppDatabase
import com.example.data.ChatHistory
import com.example.data.ContextBlock
import com.example.data.ContextRepository
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class ContextViewModel(application: Application) : AndroidViewModel(application) {

    private val database: AppDatabase = Room.databaseBuilder(
        application,
        AppDatabase::class.java, "context_drop_database"
    ).build()

    private val repository = ContextRepository(database.contextDao())

    // All elements from Repository
    val contextBlocks: StateFlow<List<ContextBlock>> = repository.allBlocks
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val chatHistories: StateFlow<List<ChatHistory>> = repository.allHistories
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // UI state filters
    private val _searchQuery = MutableStateFlow("")
    val searchQuery = _searchQuery.asStateFlow()

    private val _selectedProviderFilter = MutableStateFlow("All") // "All", "Claude", "ChatGPT", "Gemini", "General"
    val selectedProviderFilter = _selectedProviderFilter.asStateFlow()

    private val _selectedCategoryFilter = MutableStateFlow("All") // "All", "System", "Code", "Instruction", "Persona"
    val selectedCategoryFilter = _selectedCategoryFilter.asStateFlow()

    // Workspace & Mixer selection stack
    private val _selectedBlocksForMix = MutableStateFlow<List<ContextBlock>>(emptyList())
    val selectedBlocksForMix = _selectedBlocksForMix.asStateFlow()

    private val _mixedSeparator = MutableStateFlow("\n\n=== NEXT CONTEXT ===\n\n")
    val mixedSeparator = _mixedSeparator.asStateFlow()

    init {
        // Prepopulate empty database with high-quality sample developer context elements
        viewModelScope.launch {
            if (repository.getBlocksCount() == 0) {
                prepopulateDefaultBlocks()
            }
            if (repository.getHistoriesCount() == 0) {
                prepopulateDefaultChatHistories()
            }
        }
    }

    // Filtered lists
    val filteredBlocks: StateFlow<List<ContextBlock>> = combine(
        contextBlocks, searchQuery, selectedProviderFilter, selectedCategoryFilter
    ) { blocks, query, provider, category ->
        blocks.filter { block ->
            val matchesQuery = block.title.contains(query, ignoreCase = true) ||
                    block.content.contains(query, ignoreCase = true) ||
                    block.tags.contains(query, ignoreCase = true)
            val matchesProvider = provider == "All" || block.model.equals(provider, ignoreCase = true)
            val matchesCategory = category == "All" || block.category.equals(category, ignoreCase = true)

            matchesQuery && matchesProvider && matchesCategory
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Search query setters
    fun setSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun setProviderFilter(provider: String) {
        _selectedProviderFilter.value = provider
    }

    fun setCategoryFilter(category: String) {
        _selectedCategoryFilter.value = category
    }

    fun setSeparator(separator: String) {
        _mixedSeparator.value = separator
    }

    // Mixer Management
    fun toggleBlockInMixer(block: ContextBlock) {
        val current = _selectedBlocksForMix.value.toMutableList()
        if (current.any { it.id == block.id }) {
            current.removeAll { it.id == block.id }
        } else {
            current.add(block)
        }
        _selectedBlocksForMix.value = current
    }

    fun clearMixer() {
        _selectedBlocksForMix.value = emptyList()
    }

    fun reorderMixerItem(from: Int, to: Int) {
        val current = _selectedBlocksForMix.value.toMutableList()
        if (from in current.indices && to in current.indices) {
            val item = current.removeAt(from)
            current.add(to, item)
            _selectedBlocksForMix.value = current
        }
    }

    // DB Operations
    fun saveBlock(id: Int = 0, title: String, content: String, model: String, category: String, tags: String) {
        viewModelScope.launch {
            val block = ContextBlock(
                id = id,
                title = title,
                content = content,
                model = model,
                category = category,
                tags = tags
            )
            repository.insertBlock(block)
        }
    }

    fun deleteBlock(block: ContextBlock) {
        viewModelScope.launch {
            // Remove from active mixer if present
            val currentMix = _selectedBlocksForMix.value.toMutableList()
            currentMix.removeAll { it.id == block.id }
            _selectedBlocksForMix.value = currentMix

            repository.deleteBlock(block)
        }
    }

    fun saveChatHistory(title: String, rawTranscript: String, model: String, tags: String) {
        viewModelScope.launch {
            val history = ChatHistory(
                title = title,
                rawTranscript = rawTranscript,
                model = model,
                tags = tags
            )
            repository.insertHistory(history)
        }
    }

    fun deleteChatHistory(history: ChatHistory) {
        viewModelScope.launch {
            repository.deleteHistory(history)
        }
    }

    private suspend fun prepopulateDefaultBlocks() {
        val defaultBlocks = listOf(
            ContextBlock(
                title = "System: Software Architect Rules",
                content = """You are a Senior Software Architect specializing in clean coding standards, secure patterns, and DRY systems. 
Whenever writing code:
- Ensure strict type safety and modular components.
- Explain trade-offs in performance vs readability.
- Keep comments concise and focused precisely on "Why" rather than "What".""",
                model = "General",
                category = "System",
                tags = "system,architect,clean-code"
            ),
            ContextBlock(
                title = "Claude: Crafting Persona",
                content = """You are Claude, a deeply thoughtful, conversational, and precise AI engineer. 
- Use rich markdown layouts, tables, and nested checklists to structure answers.
- Speak in a calm, analytical, and professional tone.
- Give highly contextual real-world examples and anticipate edge cases before writing code.""",
                model = "Claude",
                category = "Persona",
                tags = "persona,thoughtful,creative"
            ),
            ContextBlock(
                title = "ChatGPT: Python Fast-Track",
                content = """Implement complete Python files utilizing typing, pydantic schemas, and robust error handlers. 
- Avoid truncated code blocks or placeholders.
- Always implement comprehensive unit tests (pytest) matching structural classes.
- Recommend standard logging over simple print statements.""",
                model = "ChatGPT",
                category = "Code",
                tags = "python,backend,pytest"
            ),
            ContextBlock(
                title = "Gemini: Kotlin & Jetpack Compose Spec",
                content = """Use Kotlin 2.1.0 with modern declarative Jetpack Compose UI state management.
- Center layouts inside proper Material 3 Scaffolds.
- Handle Edge-to-Edge window insets correctly using dynamic top/bottom paddings.
- Expose state properly through StateFlow and ViewModel constructor injections.""",
                model = "Gemini",
                category = "Code",
                tags = "kotlin,android,compose,ksp"
            ),
            ContextBlock(
                title = "DB Schema: ContextDrop Offline Models",
                content = """// Room SQLite schema for ContextDrop
@Entity(tableName = "context_blocks")
data class ContextBlock(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val title: String,
    val content: String,
    val model: String, 
    val category: String, 
    val tags: String, 
    val timestamp: Long
)""",
                model = "General",
                category = "Instruction",
                tags = "schema,database,offline"
            )
        )

        for (block in defaultBlocks) {
            repository.insertBlock(block)
        }
    }

    private suspend fun prepopulateDefaultChatHistories() {
        val defaultHistories = listOf(
            ChatHistory(
                title = "Optimizing Room DB Insertions",
                rawTranscript = """User: How do I handle batch inserts in Room efficiently with background threads?
Assistant: You should do that inside a database transaction to prevent SQLite from creating and tearing down multiple file journal entities. Wrap your insertion block inside:
db.withTransaction {
    for (item in items) {
        dao.insert(item)
    }
}""",
                model = "Gemini",
                tags = "room,transaction,coroutines"
            )
        )

        for (history in defaultHistories) {
            repository.insertHistory(history)
        }
    }
}
