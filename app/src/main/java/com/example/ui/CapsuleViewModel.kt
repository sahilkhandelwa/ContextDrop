package com.example.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.data.Capsule
import com.example.data.CapsuleRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed interface Screen {
    object Home : Screen
    data class WebViewScreen(val aiName: String, val url: String) : Screen
    object Library : Screen
}

class CapsuleViewModel(private val repository: CapsuleRepository) : ViewModel() {

    // Current navigation screen
    var currentScreen = androidx.compose.runtime.mutableStateOf<Screen>(Screen.Home)

    // Loaded capsules
    val capsules: StateFlow<List<Capsule>> = repository.allCapsules
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    // Save capsule dialog state
    var showSaveDialog = androidx.compose.runtime.mutableStateOf(false)
    var capsuleNameInput = androidx.compose.runtime.mutableStateOf("")
    var tempExtractedContent = androidx.compose.runtime.mutableStateOf("")
    var tempAiName = androidx.compose.runtime.mutableStateOf("")

    // Detail dialog state
    var selectedCapsuleForDetail = androidx.compose.runtime.mutableStateOf<Capsule?>(null)

    fun navigateTo(screen: Screen) {
        currentScreen.value = screen
    }

    fun openSaveDialog(aiName: String, content: String) {
        tempAiName.value = aiName
        val dateStr = java.text.SimpleDateFormat("MMM dd, yyyy HH:mm", java.util.Locale.getDefault()).format(java.util.Date())
        capsuleNameInput.value = "$aiName - $dateStr"
        tempExtractedContent.value = content
        showSaveDialog.value = true
    }

    fun saveCapsule() {
        val name = capsuleNameInput.value.trim()
        val content = tempExtractedContent.value
        val ai = tempAiName.value
        if (name.isNotEmpty() && content.isNotEmpty()) {
            viewModelScope.launch {
                repository.insert(
                    Capsule(
                        name = name,
                        aiName = ai,
                        content = content
                    )
                )
                showSaveDialog.value = false
                capsuleNameInput.value = ""
                tempExtractedContent.value = ""
            }
        }
    }

    fun deleteCapsule(id: Int) {
        viewModelScope.launch {
            repository.deleteById(id)
            if (selectedCapsuleForDetail.value?.id == id) {
                selectedCapsuleForDetail.value = null
            }
        }
    }
}

class CapsuleViewModelFactory(private val repository: CapsuleRepository) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(CapsuleViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return CapsuleViewModel(repository) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
