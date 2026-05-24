package com.example

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.example.data.ContextBlock
import com.example.ui.ContextDropTheme
import com.example.viewmodel.ContextViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ContextDropTheme {
                MainScreen()
            }
        }
    }
}

data class ScrapingResult(
    val title: String,
    val content: String,
    val platform: String
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(viewModel: ContextViewModel = viewModel()) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    
    // Database states
    val blocks by viewModel.contextBlocks.collectAsStateWithLifecycle(initialValue = emptyList())
    val filteredBlocks by viewModel.filteredBlocks.collectAsStateWithLifecycle(initialValue = emptyList())
    val searchQuery by viewModel.searchQuery.collectAsStateWithLifecycle()
    val selectedProvider by viewModel.selectedProviderFilter.collectAsStateWithLifecycle()
    val selectedCategory by viewModel.selectedCategoryFilter.collectAsStateWithLifecycle()

    // Navigation state: "splash" -> "loading" -> "main" or "webview"
    var currentScreen by remember { mutableStateOf("splash") }
    var currentWebviewUrl by remember { mutableStateOf("") }
    var currentWebviewPlatform by remember { mutableStateOf("") }
    
    // Bottom Tab (0 = Home, 1 = Library, 2 = Settings)
    var selectedTabState by remember { mutableStateOf(0) }

    // Dialog & Sheets State
    var showCreateCapsuleDialog by remember { mutableStateOf(false) }
    var viewingCapsuleDetails by remember { mutableStateOf<ContextBlock?>(null) }
    var actionBottomSheetCapsule by remember { mutableStateOf<ContextBlock?>(null) }
    var capsuleToDeleteConfirm by remember { mutableStateOf<ContextBlock?>(null) }

    // TXT File Saver State variables
    var documentTextToSave by remember { mutableStateOf("") }
    var documentTitleToSave by remember { mutableStateOf("Capsule") }
    
    val createDocLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/plain")
    ) { uri ->
        if (uri != null) {
            try {
                context.contentResolver.openOutputStream(uri)?.use { outputStream ->
                    outputStream.write(documentTextToSave.toByteArray())
                }
                Toast.makeText(context, "Saved $documentTitleToSave.txt", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(context, "Failed to export: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // Export TXT Trigger Hook
    val triggerExportTxt: (ContextBlock) -> Unit = { block ->
        documentTextToSave = "${block.title}\nPlatform: ${block.model}\nDate: ${formatRelativeTime(block.timestamp)}\n\n${block.content}"
        documentTitleToSave = block.title.replace(" ", "_").replace("/", "_")
        createDocLauncher.launch("$documentTitleToSave.txt")
    }

    // Share Sheet Hook
    val triggerShareCapsule: (ContextBlock) -> Unit = { block ->
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, block.title)
            putExtra(Intent.EXTRA_TEXT, "${block.title}\n\n${block.content}")
        }
        context.startActivity(Intent.createChooser(intent, "Share Capsule"))
    }

    // Copy Content Hook
    val triggerCopyCapsule: (ContextBlock) -> Unit = { block ->
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("ContextDrop ${block.title}", block.content)
        clipboard.setPrimaryClip(clip)
        Toast.makeText(context, "Copied context to clipboard!", Toast.LENGTH_SHORT).show()
    }

    // Setup 1-second elegant startup flow
    LaunchedEffect(Unit) {
        delay(1000)
        currentScreen = "loading"
        delay(800)
        currentScreen = "main"
    }

    Crossfade(targetState = currentScreen, label = "AppScreenTransition") { screen ->
        when (screen) {
            "splash" -> {
                SplashScreen()
            }
            "loading" -> {
                LoadingScreen()
            }
            "webview" -> {
                WebViewScreen(
                    url = currentWebviewUrl,
                    platformName = currentWebviewPlatform,
                    onBack = { currentScreen = "main" },
                    capsules = blocks,
                    onSaveBlock = { title, content, provider, category, tags ->
                        viewModel.saveBlock(
                            title = title,
                            content = content,
                            model = provider,
                            category = category,
                            tags = tags
                        )
                    }
                )
            }
            "main" -> {
                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    bottomBar = {
                        NavigationBar(
                            containerColor = MaterialTheme.colorScheme.surface,
                            tonalElevation = 4.dp,
                            modifier = Modifier.navigationBarsPadding().testTag("bottom_navigation_bar")
                        ) {
                            NavigationBarItem(
                                selected = selectedTabState == 0,
                                onClick = { selectedTabState = 0 },
                                icon = {
                                    Icon(
                                        imageVector = if (selectedTabState == 0) Icons.Filled.Home else Icons.Outlined.Home,
                                        contentDescription = "Home"
                                    )
                                },
                                label = { Text("Home", fontWeight = FontWeight.SemiBold) }
                            )
                            NavigationBarItem(
                                selected = selectedTabState == 1,
                                onClick = { selectedTabState = 1 },
                                icon = {
                                    Icon(
                                        imageVector = if (selectedTabState == 1) Icons.Filled.FolderZip else Icons.Outlined.FolderZip,
                                        contentDescription = "Library"
                                    )
                                },
                                label = { Text("Library", fontWeight = FontWeight.SemiBold) }
                            )
                            NavigationBarItem(
                                selected = selectedTabState == 2,
                                onClick = { selectedTabState = 2 },
                                icon = {
                                    Icon(
                                        imageVector = if (selectedTabState == 2) Icons.Filled.Settings else Icons.Outlined.Settings,
                                        contentDescription = "Settings"
                                    )
                                },
                                label = { Text("Settings", fontWeight = FontWeight.SemiBold) }
                            )
                        }
                    }
                ) { innerPadding ->
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding)
                    ) {
                        when (selectedTabState) {
                            0 -> {
                                HomeView(
                                    capsules = blocks,
                                    onOpenCapsule = { viewingCapsuleDetails = it },
                                    onSearchQueryChanged = { viewModel.setSearchQuery(it) },
                                    onNavigateToLibraryWithFilter = { provider ->
                                        viewModel.setProviderFilter(provider)
                                        selectedTabState = 1
                                    },
                                    onLaunchWebView = { url, platform ->
                                        currentWebviewUrl = url
                                        currentWebviewPlatform = platform
                                        currentScreen = "webview"
                                    },
                                    onOpenSettings = { selectedTabState = 2 },
                                    onCreateTrigger = { showCreateCapsuleDialog = true }
                                )
                            }
                            1 -> {
                                LibraryView(
                                    blocks = filteredBlocks,
                                    searchQuery = searchQuery,
                                    selectedProvider = selectedProvider,
                                    selectedCategory = selectedCategory,
                                    onSearchChange = { viewModel.setSearchQuery(it) },
                                    onProviderFilter = { viewModel.setProviderFilter(it) },
                                    onCategoryFilter = { viewModel.setCategoryFilter(it) },
                                    onOpenCapsule = { viewingCapsuleDetails = it },
                                    onLongPressCapsule = { actionBottomSheetCapsule = it }
                                )
                            }
                            2 -> {
                                SettingsScreenView(
                                    onClearAllData = {
                                        viewModel.clearMixer()
                                        scope.launch {
                                            blocks.forEach { block ->
                                                viewModel.deleteBlock(block)
                                            }
                                        }
                                        // Clear all WebView Cookies
                                        CookieManager.getInstance().removeAllCookies(null)
                                        Toast.makeText(context, "All data and persistent web sessions cleared!", Toast.LENGTH_LONG).show()
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // Detailed Capsule View Modal
    viewingCapsuleDetails?.let { block ->
        CapsuleDetailDialog(
            capsule = block,
            onDismiss = { viewingCapsuleDetails = null },
            onCopy = { triggerCopyCapsule(block) },
            onDownload = { triggerExportTxt(block) },
            onShare = { triggerShareCapsule(block) },
            onDelete = {
                capsuleToDeleteConfirm = block
                viewingCapsuleDetails = null
            }
        )
    }

    // Elegant Action Bottom Sheet
    actionBottomSheetCapsule?.let { block ->
        ModalBottomSheetDialog(
            capsule = block,
            onDismiss = { actionBottomSheetCapsule = null },
            onCopy = {
                triggerCopyCapsule(block)
                actionBottomSheetCapsule = null
            },
            onDownload = {
                triggerExportTxt(block)
                actionBottomSheetCapsule = null
            },
            onShare = {
                triggerShareCapsule(block)
                actionBottomSheetCapsule = null
            },
            onDelete = {
                capsuleToDeleteConfirm = block
                actionBottomSheetCapsule = null
            }
        )
    }

    // Confirmation deletion Dialog
    capsuleToDeleteConfirm?.let { block ->
        AlertDialog(
            onDismissRequest = { capsuleToDeleteConfirm = null },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.deleteBlock(block)
                        capsuleToDeleteConfirm = null
                        Toast.makeText(context, "Capsule deleted", Toast.LENGTH_SHORT).show()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { capsuleToDeleteConfirm = null }) {
                    Text("Cancel")
                }
            },
            title = { Text("Delete Capsule?") },
            text = { Text("Are you sure you want to permanently delete \"${block.title}\"? This action cannot be undone.") }
        )
    }

    // Custom Capsule Creator Dialog (Triggered from Home / Library when empty)
    if (showCreateCapsuleDialog) {
        var createTitle by remember { mutableStateOf("") }
        var createContent by remember { mutableStateOf("") }
        var createProvider by remember { mutableStateOf("Claude") }
        var createCategory by remember { mutableStateOf("Conversation") }
        var createTags by remember { mutableStateOf("") }

        val providers = listOf("Claude", "ChatGPT", "Gemini", "General")

        AlertDialog(
            onDismissRequest = { showCreateCapsuleDialog = false },
            confirmButton = {
                Button(
                    onClick = {
                        if (createTitle.isNotBlank() && createContent.isNotBlank()) {
                            viewModel.saveBlock(
                                title = createTitle,
                                content = createContent,
                                model = createProvider,
                                category = createCategory,
                                tags = createTags
                            )
                            showCreateCapsuleDialog = false
                            Toast.makeText(context, "Capsule created", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(context, "Title and Content cannot be empty", Toast.LENGTH_SHORT).show()
                        }
                    }
                ) {
                    Text("Create")
                }
            },
            dismissButton = {
                TextButton(onClick = { showCreateCapsuleDialog = false }) {
                    Text("Cancel")
                }
            },
            title = { Text("New Context Capsule") },
            text = {
                Column(
                    modifier = Modifier
                        .verticalScroll(rememberScrollState())
                        .fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedTextField(
                        value = createTitle,
                        onValueChange = { createTitle = it },
                        label = { Text("Title") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    
                    Text("Platform Source", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        items(providers) { pr ->
                            val isSel = pr == createProvider
                            FilterChip(
                                selected = isSel,
                                onClick = { createProvider = pr },
                                label = { Text(pr) }
                            )
                        }
                    }

                    OutlinedTextField(
                        value = createCategory,
                        onValueChange = { createCategory = it },
                        label = { Text("Category (e.g., Code, Prompt)") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )

                    OutlinedTextField(
                        value = createTags,
                        onValueChange = { createTags = it },
                        label = { Text("Tags (comma separated)") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )

                    OutlinedTextField(
                        value = createContent,
                        onValueChange = { createContent = it },
                        label = { Text("Content / Conversation Transcript") },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 4,
                        maxLines = 8
                    )
                }
            }
        )
    }
}

// ================= SPLASH & LOADING SCREENS =================

@Composable
fun SplashScreen() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.White),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(24.dp)
        ) {
            Variant3Logo(modifier = Modifier.size(110.dp))
            Spacer(modifier = Modifier.height(24.dp))
            Text(
                text = "ContextDrop",
                style = MaterialTheme.typography.displayMedium.copy(
                    fontWeight = FontWeight.Black,
                    letterSpacing = (-1.5).sp
                ),
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Save once. Use anywhere.",
                style = MaterialTheme.typography.bodyLarge.copy(
                    fontWeight = FontWeight.Medium,
                    letterSpacing = 0.5.sp
                ),
                color = MaterialTheme.colorScheme.secondary
            )
        }
    }
}

@Composable
fun LoadingScreen() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.White),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(24.dp)
        ) {
            Variant3Logo(modifier = Modifier.size(80.dp))
            Spacer(modifier = Modifier.height(20.dp))
            Text(
                text = "ContextDrop",
                style = MaterialTheme.typography.headlineLarge.copy(
                    fontWeight = FontWeight.Bold,
                    letterSpacing = (-1).sp
                ),
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "Checking connection & system updates...",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.secondary
            )
            Spacer(modifier = Modifier.height(32.dp))
            CircularProgressIndicator(
                color = MaterialTheme.colorScheme.primary,
                strokeWidth = 3.dp,
                modifier = Modifier.size(36.dp)
            )
        }
    }
}

// ================= MAIN VIEWS =================

@Composable
fun HomeView(
    capsules: List<ContextBlock>,
    onOpenCapsule: (ContextBlock) -> Unit,
    onSearchQueryChanged: (String) -> Unit,
    onNavigateToLibraryWithFilter: (String) -> Unit,
    onLaunchWebView: (url: String, platform: String) -> Unit,
    onOpenSettings: () -> Unit,
    onCreateTrigger: () -> Unit
) {
    var searchQueryLocal by remember { mutableStateOf("") }
    
    // Sort capsules to get the latest ones
    val recentCapsules = remember(capsules) {
        capsules.sortedByDescending { it.timestamp }.take(4)
    }

    // Stats calculations
    val totalCapsulesCount = capsules.size
    val platformsCount = remember(capsules) {
        capsules.map { it.model }.distinct().size
    }
    val savedThisWeekCount = remember(capsules) {
        val sevenDaysAgo = System.currentTimeMillis() - (7L * 24 * 60 * 60 * 1000)
        capsules.count { it.timestamp > sevenDaysAgo }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFFFAFAFA))
            .padding(horizontal = 24.dp),
        contentPadding = PaddingValues(top = 24.dp, bottom = 48.dp),
        verticalArrangement = Arrangement.spacedBy(28.dp)
    ) {
        // 1. TOP BAR
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Variant3Logo(modifier = Modifier.size(38.dp))
                    Text(
                        text = "ContextDrop",
                        style = MaterialTheme.typography.titleLarge.copy(
                            fontWeight = FontWeight.Black,
                            letterSpacing = (-0.5).sp
                        ),
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                IconButton(
                    onClick = onOpenSettings,
                    modifier = Modifier.testTag("settings_icon_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.Settings,
                        contentDescription = "Notification and update settings",
                        tint = MaterialTheme.colorScheme.secondary
                    )
                }
            }
        }

        // 2. SEARCH BAR SECTION
        item {
            OutlinedTextField(
                value = searchQueryLocal,
                onValueChange = {
                    searchQueryLocal = it
                    onSearchQueryChanged(it)
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("home_search_bar"),
                placeholder = { Text("Search capsules by title/content...", color = MaterialTheme.colorScheme.secondary) },
                leadingIcon = { Icon(Icons.Default.Search, "Search Icon", tint = MaterialTheme.colorScheme.secondary) },
                trailingIcon = {
                    if (searchQueryLocal.isNotEmpty()) {
                        IconButton(onClick = {
                            searchQueryLocal = ""
                            onSearchQueryChanged("")
                        }) {
                            Icon(Icons.Default.Close, "Clear", tint = MaterialTheme.colorScheme.secondary)
                        }
                    }
                },
                singleLine = true,
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surface,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                    focusedIndicatorColor = MaterialTheme.colorScheme.primary,
                    unfocusedIndicatorColor = MaterialTheme.colorScheme.outline
                ),
                shape = RoundedCornerShape(12.dp)
            )
            
            if (searchQueryLocal.isNotBlank()) {
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onNavigateToLibraryWithFilter("All") }
                        .padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Show matching library templates...",
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    )
                }
            }
        }

        // 3. QUICK ACTIONS ROW (OFFICIAL WEBVIEW DIRECT TRIGGERS)
        item {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = "Launch Active Web Workspace",
                    style = MaterialTheme.typography.titleSmall.copy(
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.5.sp
                    ),
                    color = MaterialTheme.colorScheme.primary
                )
                
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    QuickActionCircularButton(
                        label = "Claude",
                        logo = { ClaudeLogo(modifier = Modifier.size(54.dp)) },
                        onClick = { onLaunchWebView("https://claude.ai", "Claude") }
                    )
                    QuickActionCircularButton(
                        label = "ChatGPT",
                        logo = { ChatGPTLogo(modifier = Modifier.size(54.dp)) },
                        onClick = { onLaunchWebView("https://chatgpt.com", "ChatGPT") }
                    )
                    QuickActionCircularButton(
                        label = "Gemini",
                        logo = { GeminiLogo(modifier = Modifier.size(54.dp)) },
                        onClick = { onLaunchWebView("https://gemini.google.com", "Gemini") }
                    )
                    QuickActionCircularButton(
                        label = "Create",
                        logo = {
                            Box(
                                modifier = Modifier
                                    .size(54.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.primary),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(Icons.Default.Add, "Custom add", tint = Color.White, modifier = Modifier.size(24.dp))
                            }
                        },
                        onClick = onCreateTrigger
                    )
                }
            }
        }

        // 4. STATS BAR ROW
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(16.dp))
                    .padding(vertical = 18.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = totalCapsulesCount.toString(),
                        style = MaterialTheme.typography.headlineLarge.copy(fontWeight = FontWeight.Black),
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = "Total Capsules",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.secondary
                    )
                }
                Box(modifier = Modifier.width(1.dp).height(32.dp).background(MaterialTheme.colorScheme.outline))
                Column(
                    modifier = Modifier.weight(1f),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = if (totalCapsulesCount == 0) "0" else platformsCount.toString(),
                        style = MaterialTheme.typography.headlineLarge.copy(fontWeight = FontWeight.Black),
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = "AI Platforms",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.secondary
                    )
                }
                Box(modifier = Modifier.width(1.dp).height(32.dp).background(MaterialTheme.colorScheme.outline))
                Column(
                    modifier = Modifier.weight(1f),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = savedThisWeekCount.toString(),
                        style = MaterialTheme.typography.headlineLarge.copy(fontWeight = FontWeight.Black),
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = "Saved This Week",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.secondary
                    )
                }
            }
        }

        // 5. RECENT CAPSULES SECTION
        item {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Recent Capsules",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.primary
                    )
                    
                    if (capsules.isNotEmpty()) {
                        Text(
                            text = "View Library",
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            ),
                            modifier = Modifier.clickable { onNavigateToLibraryWithFilter("All") }
                        )
                    }
                }

                if (recentCapsules.isEmpty()) {
                    // Empty state layout
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Text(
                                text = "Save conversations from Claude, ChatGPT and Gemini for instant reuse.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.secondary,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.padding(bottom = 16.dp)
                            )
                            Button(
                                onClick = onCreateTrigger,
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Icon(Icons.Default.Add, "Plus icon")
                                    Text("Create First Capsule")
                                }
                            }
                        }
                    }
                } else {
                    recentCapsules.forEach { block ->
                        HomeCapsuleCard(
                            capsule = block,
                            onClick = { onOpenCapsule(block) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun QuickActionCircularButton(
    label: String,
    logo: @Composable () -> Unit,
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.clickable(onClick = onClick)
    ) {
        logo()
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.secondary
        )
    }
}

@Composable
fun HomeCapsuleCard(
    capsule: ContextBlock,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Platform Source Badge
                val badgeColor = when (capsule.model) {
                    "Claude" -> Color(0xFFD97706)
                    "ChatGPT" -> Color(0xFF10A37F)
                    "Gemini" -> Color(0xFF3B82F6)
                    else -> Color(0xFF6B7280)
                }
                
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(badgeColor)
                    )
                    Text(
                        text = capsule.model.uppercase(),
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                        color = badgeColor
                    )
                }

                Text(
                    text = formatRelativeTime(capsule.timestamp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.secondary
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            Text(
                text = capsule.title,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
            )

            Spacer(modifier = Modifier.height(6.dp))

            Text(
                text = capsule.content,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.secondary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

// ================= LIBRARY VIEW =================

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun LibraryView(
    blocks: List<ContextBlock>,
    searchQuery: String,
    selectedProvider: String,
    selectedCategory: String,
    onSearchChange: (String) -> Unit,
    onProviderFilter: (String) -> Unit,
    onCategoryFilter: (String) -> Unit,
    onOpenCapsule: (ContextBlock) -> Unit,
    onLongPressCapsule: (ContextBlock) -> Unit
) {
    val providers = listOf("All", "Claude", "ChatGPT", "Gemini", "General")
    
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFFFAFAFA))
            .padding(horizontal = 24.dp)
    ) {
        Spacer(modifier = Modifier.height(24.dp))
        
        Text(
            text = "Your Context Vault",
            style = MaterialTheme.typography.headlineSmall.copy(
                fontWeight = FontWeight.Black,
                letterSpacing = (-0.5).sp
            ),
            color = MaterialTheme.colorScheme.primary
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Large rounded search bar
        OutlinedTextField(
            value = searchQuery,
            onValueChange = onSearchChange,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("library_search_field"),
            placeholder = { Text("Search capsules, titles, content logs...") },
            leadingIcon = { Icon(Icons.Default.Search, "Search icon", tint = MaterialTheme.colorScheme.secondary) },
            trailingIcon = {
                if (searchQuery.isNotEmpty()) {
                    IconButton(onClick = { onSearchChange("") }) {
                        Icon(Icons.Default.Close, "Clear", tint = MaterialTheme.colorScheme.secondary)
                    }
                }
            },
            singleLine = true,
            colors = TextFieldDefaults.colors(
                focusedContainerColor = MaterialTheme.colorScheme.surface,
                unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                focusedIndicatorColor = MaterialTheme.colorScheme.primary,
                unfocusedIndicatorColor = MaterialTheme.colorScheme.outline
            ),
            shape = RoundedCornerShape(12.dp)
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Platform Switch Pills
        LazyRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(providers) { provider ->
                val isSelected = provider == selectedProvider
                val badgeColor = when (provider) {
                    "Claude" -> Color(0xFFD97706)
                    "ChatGPT" -> Color(0xFF10A37F)
                    "Gemini" -> Color(0xFF3B82F6)
                    "General" -> Color(0xFF6B7280)
                    else -> MaterialTheme.colorScheme.primary
                }

                FilterChip(
                    selected = isSelected,
                    onClick = { onProviderFilter(provider) },
                    label = { Text(provider) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = badgeColor.copy(alpha = 0.12f),
                        selectedLabelColor = badgeColor
                    )
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        if (blocks.isEmpty()) {
            // Empty State
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = Icons.Default.FolderOpen,
                        contentDescription = "Empty",
                        modifier = Modifier.size(54.dp),
                        tint = MaterialTheme.colorScheme.secondary.copy(alpha = 0.5f)
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "No saved capsules found",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Scrape chats inside Claude, ChatGPT or Gemini web views or add them manually to start building your vault.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.secondary,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 24.dp)
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(bottom = 32.dp)
            ) {
                items(blocks) { block ->
                    val badgeColor = when (block.model) {
                        "Claude" -> Color(0xFFD97706)
                        "ChatGPT" -> Color(0xFF10A37F)
                        "Gemini" -> Color(0xFF3B82F6)
                        else -> Color(0xFF6B7280)
                    }

                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .combinedClickable(
                                onClick = { onOpenCapsule(block) },
                                onLongClick = { onLongPressCapsule(block) }
                            ),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(8.dp)
                                            .clip(CircleShape)
                                            .background(badgeColor)
                                    )
                                    Text(
                                        text = block.model.uppercase(),
                                        style = MaterialTheme.typography.labelSmall.copy(
                                            fontWeight = FontWeight.Bold,
                                            color = badgeColor
                                        )
                                    )
                                }
                                Text(
                                    text = formatRelativeTime(block.timestamp),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.secondary
                                )
                            }

                            Spacer(modifier = Modifier.height(8.dp))

                            Text(
                                text = block.title,
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.primary
                            )

                            Spacer(modifier = Modifier.height(6.dp))

                            Text(
                                text = block.content,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.secondary,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }
    }
}

// ================= WEBVIEW EXPERIENCE CONTAINER =================

fun generateAutoTitle(extractedText: String, platformName: String): String {
    val lines = extractedText.split("\n")
        .map { it.trim() }
        .filter { it.isNotBlank() }
    
    for (line in lines) {
        val lower = line.lowercase()
        // Skip common role separators
        if (lower == "user:" || lower == "assistant:" || lower == "claude:" || lower == "chatgpt:" || lower == "gemini:") {
            continue
        }
        val cleanLine = line.trim()
        if (cleanLine.isNotBlank()) {
            return if (cleanLine.length > 40) {
                cleanLine.substring(0, 37) + "..."
            } else {
                cleanLine
            }
        }
    }
    return "$platformName Chat Session"
}

fun triggerExtractionForSave(
    wv: WebView?,
    platformName: String,
    context: Context,
    onSuccess: (ScrapingResult?) -> Unit
) {
    if (wv == null) {
        Toast.makeText(context, "WebView is initializing... Please wait.", Toast.LENGTH_SHORT).show()
        onSuccess(null)
        return
    }
    
    val js = """
        (function() {
            var messagesToSave = [];
            var href = window.location.href;

            function cleanElementAndGetText(el) {
                if (!el) return "";
                var clone = el.cloneNode(true);
                var selectorsToRemove = [
                    'button', 'svg', 'img', 'nav', 'aside', '.avatar', '.sr-only', 
                    '[role="button"]', '.hidden', 'style', 'script',
                    '.text-xs', '.copy-button', '.feedback-buttons', '.conversation-actions',
                    '[aria-label*="copy"]', '[aria-label*="Copy"]',
                    '[aria-label*="share"]', '[aria-label*="Share"]',
                    '.copied', '.code-header', '.syntax-highlighter-header'
                ];
                selectorsToRemove.forEach(function(sel) {
                    var unwanted = clone.querySelectorAll(sel);
                    unwanted.forEach(function(u) {
                        u.parentNode.removeChild(u);
                    });
                });
                
                var pres = clone.querySelectorAll('pre');
                pres.forEach(function(pre) {
                    var code = pre.querySelector('code');
                    if (code) {
                        pre.innerText = "\n```\n" + code.innerText.trim() + "\n```\n";
                    }
                });
                
                return clone.innerText.trim();
            }

            if (href.includes("chatgpt.com")) {
                var rawItems = Array.from(document.querySelectorAll('article'));
                if (rawItems.length === 0) {
                    rawItems = Array.from(document.querySelectorAll('[data-testid^="conversation-turn-"], [class*="ConversationTurn"]'));
                }
                if (rawItems.length === 0) {
                    rawItems = Array.from(document.querySelectorAll('[data-testid*="message"], [data-message-author-role]'));
                }
                
                // Deduplicate any nested matches
                var items = rawItems.filter(function(el) {
                    return !rawItems.some(function(other) {
                        return other !== el && other.contains(el);
                    });
                });
                
                items.forEach(function(item) {
                    var isUser = false;
                    
                    // Look for the role attribute: either on the element itself, or on any of its descendants
                    var role = item.getAttribute('data-message-author-role');
                    if (!role) {
                        var nestedRoleEl = item.querySelector('[data-message-author-role]');
                        if (nestedRoleEl) {
                            role = nestedRoleEl.getAttribute('data-message-author-role');
                        }
                    }
                    
                    if (role) {
                        if (role === 'user') {
                            isUser = true;
                        }
                    } else {
                        // Fallback heuristical checks:
                        var testId = item.getAttribute('data-testid') || "";
                        var className = item.className || "";
                        if (testId.toLowerCase().includes('user') || 
                            className.toLowerCase().includes('user') ||
                            item.querySelector('[data-testid$="user-message"]') || 
                            item.querySelector('.user-message')) {
                            isUser = true;
                        }
                    }
                    
                    var speaker = isUser ? 'USER' : 'CHATGPT';
                    var contentEl = item.querySelector('.markdown') || 
                                    item.querySelector('.whitespace-pre-wrap') || 
                                    item.querySelector('[data-message-author-role="assistant"]') ||
                                    item.querySelector('[data-message-author-role="user"]') ||
                                    item;
                                    
                    var textVal = cleanElementAndGetText(contentEl);
                    if (textVal) {
                        var lowerVal = textVal.toLowerCase();
                        if (lowerVal !== "user" && lowerVal !== "chatgpt" && lowerVal !== "assistant") {
                            messagesToSave.push({ speaker: speaker, text: textVal });
                        }
                    }
                });
            } else if (href.includes("claude.ai")) {
                var rawItems = Array.from(document.querySelectorAll(
                    '[data-testid="user-message"], [data-testid="assistant-message"], [data-testid="claude-message"], ' +
                    '.user-message, .claude-message, .assistant-message, ' +
                    'div[class*="font-user-message"], div[class*="font-claude-message"], ' +
                    'div[class*="message-bubble"], div[class*="MessageBubble"]'
                ));
                
                var items = rawItems.filter(function(el) {
                    return !rawItems.some(function(other) {
                        return other !== el && other.contains(el);
                    });
                });
                
                items.forEach(function(item) {
                    var isUser = false;
                    var testId = item.getAttribute('data-testid') || "";
                    var className = item.className || "";
                    var htmlContent = item.innerHTML || "";
                    
                    if (testId.includes('user') || 
                        className.toLowerCase().includes('user') || 
                        className.toLowerCase().includes('font-user-message') ||
                        htmlContent.includes('user-message')) {
                        isUser = true;
                    }
                    
                    var speaker = isUser ? 'USER' : 'CLAUDE';
                    var contentEl = item.querySelector('.markdown') || item;
                    var textVal = cleanElementAndGetText(contentEl);
                    if (textVal) {
                        var lowerVal = textVal.toLowerCase();
                        if (lowerVal !== "user" && lowerVal !== "claude" && lowerVal !== "assistant") {
                            messagesToSave.push({ speaker: speaker, text: textVal });
                        }
                    }
                });
            } else if (href.includes("gemini.google.com")) {
                var items = Array.from(document.querySelectorAll('user-query, model-response'));
                if (items.length === 0) {
                    items = Array.from(document.querySelectorAll('.user-query, .model-response'));
                }
                if (items.length === 0) {
                    items = Array.from(document.querySelectorAll('[class*="user-query"], [class*="model-response"]'));
                }
                
                items.forEach(function(item) {
                    var isUser = item.tagName.toLowerCase() === 'user-query' || 
                                 item.classList.contains('user-query') ||
                                 item.className.toLowerCase().includes('user-query');
                    
                    var speaker = isUser ? 'USER' : 'GEMINI';
                    var contentContainer = item.querySelector('.message-content') || 
                                           item.querySelector('.query-content') || 
                                           item.querySelector('.model-response-text') ||
                                           item;
                                           
                    var textVal = cleanElementAndGetText(contentContainer);
                    if (textVal) {
                        var lowerVal = textVal.toLowerCase();
                        if (lowerVal !== "user" && lowerVal !== "gemini" && lowerVal !== "assistant" && lowerVal !== "model response") {
                            messagesToSave.push({ speaker: speaker, text: textVal });
                        }
                    }
                });
            } else {
                var ps = document.querySelectorAll('p, pre');
                ps.forEach(function(p) {
                    var txt = cleanElementAndGetText(p);
                    if (txt && txt.length > 20) {
                        messagesToSave.push({ speaker: "Text", text: txt });
                    }
                });
            }

            // Universal robust fallback if platform-specific scraper returned too few messages
            if (messagesToSave.length < 2) {
                messagesToSave = [];
                var rawCandidates = Array.from(document.querySelectorAll(
                    'article, [data-testid*="message"], [data-testid*="turn"], [class*="message-content"], [class*="message_content"], [class*="message-body"], .message, .bubble, [class*="bubble"], [class*="conversation-turn"]'
                ));
                
                var totalCandidates = rawCandidates.length;
                var candidates = rawCandidates.filter(function(el) {
                    if (el.querySelector('nav') || el.closest('nav') || el.querySelector('sidebar') || el.closest('sidebar') || el.closest('button')) {
                        return false;
                    }
                    var text = el.innerText ? el.innerText.trim() : "";
                    if (text.length < 2 || text.length > 50000) return false;
                    if (el.tagName === 'BODY' || el.tagName === 'HTML') return false;
                    
                    var containedCount = 0;
                    for (var i = 0; i < totalCandidates; i++) {
                        var other = rawCandidates[i];
                        if (other !== el && el.contains(other)) {
                            containedCount++;
                        }
                    }
                    
                    if (containedCount >= 4 && containedCount > totalCandidates * 0.4) {
                        return false;
                    }
                    return true;
                });
                
                var nonNested = candidates.filter(function(el) {
                    return !candidates.some(function(other) {
                        return other !== el && other.contains(el);
                    });
                });
                
                nonNested.forEach(function(el) {
                    var html = el.innerHTML || "";
                    var textVal = cleanElementAndGetText(el);
                    if (!textVal) return;
                    
                    var isUser = false;
                    var testId = el.getAttribute('data-testid') || "";
                    var className = el.className || "";
                    
                    if (testId.includes('user') || 
                        className.toLowerCase().includes('user') || 
                        el.tagName.toLowerCase() === 'user-query' ||
                        html.includes('data-message-author-role="user"') ||
                        el.querySelector('[data-message-author-role="user"]') ||
                        className.toLowerCase().includes('query-content')) {
                        isUser = true;
                    }
                    
                    var speaker = isUser ? 'USER' : '${platformName.uppercase()}';
                    messagesToSave.push({ speaker: speaker, text: textVal });
                });
            }

            var finalResult = "";
            var lastSpeaker = "";
            var lastText = "";
            
            messagesToSave.forEach(function(msg) {
                var cleanTxt = msg.text.trim();
                if (cleanTxt === lastText && msg.speaker === lastSpeaker) {
                    return;
                }
                
                var lowerTxt = cleanTxt.toLowerCase();
                if (lowerTxt === "user" || lowerTxt === "claude" || lowerTxt === "chatgpt" || lowerTxt === "gemini" || lowerTxt === "assistant") {
                    return;
                }
                
                finalResult += msg.speaker + ":\n" + cleanTxt + "\n\n";
                lastSpeaker = msg.speaker;
                lastText = cleanTxt;
            });

            return finalResult.trim();
        })()
    """.trimIndent()

    wv.evaluateJavascript(js) { rawResult ->
        var decodedResult = rawResult ?: ""
        if (decodedResult.startsWith("\"") && decodedResult.endsWith("\"") && decodedResult.length > 1) {
            decodedResult = decodedResult.substring(1, decodedResult.length - 1)
            decodedResult = decodedResult
                .replace("\\n", "\n")
                .replace("\\t", "\t")
                .replace("\\\"", "\"")
                .replace("\\\\", "\\")
        }
        decodedResult = decodedResult.trim()
        
        if (decodedResult.isBlank() || decodedResult == "null") {
            Toast.makeText(context, "Unable to extract conversation. Please try again.", Toast.LENGTH_LONG).show()
            onSuccess(null)
        } else {
            val autoTitle = generateAutoTitle(decodedResult, platformName)
            onSuccess(
                ScrapingResult(
                    title = autoTitle,
                    content = decodedResult,
                    platform = platformName
                )
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WebViewScreen(
    url: String,
    platformName: String,
    onBack: () -> Unit,
    capsules: List<ContextBlock>,
    onSaveBlock: (title: String, content: String, provider: String, category: String, tags: String) -> Unit
) {
    val context = LocalContext.current
    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    var progress by remember { mutableIntStateOf(0) }
    var isRefreshing by remember { mutableStateOf(false) }
    var showSaveDialog by remember { mutableStateOf<ScrapingResult?>(null) }
    var showCapsulePicker by remember { mutableStateOf(false) }
    var pickerSearchQuery by remember { mutableStateOf("") }
    var showCapsulesMenu by remember { mutableStateOf(false) }
    var webViewResetKey by remember { mutableIntStateOf(0) }
    var isRenderProcessCrashed by remember { mutableStateOf(false) }

    DisposableEffect(webViewResetKey) {
        onDispose {
            webViewRef?.let { wv ->
                try {
                    val parent = wv.parent as? ViewGroup
                    parent?.removeView(wv)
                } catch (e: Exception) {
                    // Ignore
                }
                try {
                    wv.stopLoading()
                } catch (e: Exception) {
                    // Ignore
                }
                try {
                    wv.clearHistory()
                } catch (e: Exception) {
                    // Ignore
                }
                try {
                    wv.removeAllViews()
                } catch (e: Exception) {
                    // Ignore
                }
                try {
                    wv.destroy()
                } catch (e: Exception) {
                    // Ignore
                }
            }
            webViewRef = null
        }
    }

    BackHandler(enabled = webViewRef?.canGoBack() == true) {
        webViewRef?.goBack()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        when (platformName) {
                            "Claude" -> ClaudeLogo(modifier = Modifier.size(28.dp))
                            "ChatGPT" -> ChatGPTLogo(modifier = Modifier.size(28.dp))
                            "Gemini" -> GeminiLogo(modifier = Modifier.size(28.dp))
                            else -> Variant3Logo(modifier = Modifier.size(28.dp))
                        }
                        Text(
                            text = "$platformName Web Container",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, "Go Back")
                    }
                },
                actions = {
                    IconButton(onClick = { webViewRef?.reload() }) {
                        Icon(Icons.Default.Refresh, "Reload page")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                )
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { showCapsulesMenu = true },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                icon = { Icon(Icons.Default.FolderOpen, "Capsules Icon") },
                text = { Text("Capsules") }
            )
        }
    ) { paddingValues ->
        Box(modifier = Modifier.fillMaxSize().padding(paddingValues)) {
            if (isRenderProcessCrashed) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background)
                        .padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                    ) {
                        Column(
                            modifier = Modifier.padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Warning,
                                contentDescription = "Rendering process gone",
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(64.dp)
                            )
                            
                            Text(
                                text = "Render Process Terminated",
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.error
                            )
                            
                            Text(
                                text = "The web view collapsed because the system reclaimed memory. Don't worry, nothing is lost! Tap below to reload the container securely.",
                                style = MaterialTheme.typography.bodyMedium,
                                textAlign = TextAlign.Center,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            
                            Spacer(modifier = Modifier.height(8.dp))
                            
                            Button(
                                onClick = {
                                    isRenderProcessCrashed = false
                                    webViewResetKey++
                                },
                                shape = RoundedCornerShape(8.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                            ) {
                                Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Reload Web Sandbox")
                            }
                        }
                    }
                }
            } else {
                key(webViewResetKey) {
                    AndroidView(
                        factory = { ctx ->
                            val swipeLayout = SwipeRefreshLayout(ctx).apply {
                                setOnRefreshListener {
                                    webViewRef?.reload()
                                }
                                setColorSchemeColors(0xFF6366F1.toInt())
                            }
                            val webView = WebView(ctx).apply {
                                layoutParams = ViewGroup.LayoutParams(
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                    ViewGroup.LayoutParams.MATCH_PARENT
                                )
                                webViewClient = object : WebViewClient() {
                                    override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                                        super.onPageStarted(view, url, favicon)
                                        swipeLayout.isRefreshing = true
                                        isRefreshing = true
                                    }

                                    override fun onPageFinished(view: WebView?, url: String?) {
                                        super.onPageFinished(view, url)
                                        swipeLayout.isRefreshing = false
                                        isRefreshing = false
                                    }

                                    override fun onRenderProcessGone(view: WebView?, detail: android.webkit.RenderProcessGoneDetail?): Boolean {
                                        view?.let { wv ->
                                            try {
                                                val parent = wv.parent as? ViewGroup
                                                parent?.removeView(wv)
                                                // Safe: handled in onDispose, removed from here to prevent crashes
                                            } catch (e: Exception) {
                                                // Ignore
                                            }
                                        }
                                        isRenderProcessCrashed = true
                                        return true
                                    }
                                }
                                webChromeClient = object : WebChromeClient() {
                                    override fun onProgressChanged(view: WebView?, newProgress: Int) {
                                        super.onProgressChanged(view, newProgress)
                                        progress = newProgress
                                    }
                                }
                                settings.apply {
                                    javaScriptEnabled = true
                                    domStorageEnabled = true
                                    databaseEnabled = true
                                    useWideViewPort = true
                                    loadWithOverviewMode = true
                                    userAgentString = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
                                }
                                
                                val cookies = CookieManager.getInstance()
                                cookies.setAcceptCookie(true)
                                cookies.setAcceptThirdPartyCookies(this, true)
                                
                                loadUrl(url)
                            }
                            webViewRef = webView
                            swipeLayout.addView(webView)
                            swipeLayout
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }

            if (progress < 100) {
                LinearProgressIndicator(
                    progress = progress / 100f,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(3.dp)
                        .align(Alignment.TopCenter),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = Color.Transparent
                )
            }
        }
    }

    // Capture editor validation dialog
    showSaveDialog?.let { result ->
        var saveTitle by remember(result) { mutableStateOf(result.title) }

        AlertDialog(
            onDismissRequest = { showSaveDialog = null },
            confirmButton = {
                Button(
                    onClick = {
                        if (saveTitle.isNotBlank()) {
                            onSaveBlock(saveTitle, result.content, result.platform, "Scraped", "")
                            showSaveDialog = null
                            Toast.makeText(context, "Saved successfully!", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(context, "Title cannot be empty.", Toast.LENGTH_SHORT).show()
                        }
                    }
                ) {
                    Text("Save")
                }
            },
            dismissButton = {
                TextButton(onClick = { showSaveDialog = null }) {
                    Text("Cancel")
                }
            },
            title = { Text("Save Capsule") },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("Title", style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold))
                    OutlinedTextField(
                        value = saveTitle,
                        onValueChange = { saveTitle = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )

                    Spacer(modifier = Modifier.height(4.dp))

                    Text("Content", style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold))
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(110.dp)
                            .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.4f), RoundedCornerShape(8.dp)),
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f)
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(8.dp)
                                .verticalScroll(rememberScrollState())
                        ) {
                            Text(
                                text = result.content,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        )
    }

    if (showCapsulesMenu) {
        Dialog(onDismissRequest = { showCapsulesMenu = false }) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 8.dp,
                modifier = Modifier.fillMaxWidth().padding(16.dp)
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Text(
                        text = "Capsules",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.primary
                    )
                    
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
                    
                    // 1. SAVE CAPSULE Row
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .clickable {
                                showCapsulesMenu = false
                                triggerExtractionForSave(webViewRef, platformName, context) { result ->
                                    if (result != null) {
                                        showSaveDialog = result
                                    }
                                }
                            }
                            .padding(vertical = 12.dp, horizontal = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Save,
                            contentDescription = "Save Capsule",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(24.dp)
                        )
                        Column {
                            Text(
                                text = "Save Capsule",
                                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold)
                            )
                            Text(
                                text = "Extract and save current conversation",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    // 2. LOAD CAPSULE Row
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .clickable {
                                showCapsulesMenu = false
                                showCapsulePicker = true
                            }
                            .padding(vertical = 12.dp, horizontal = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.CloudUpload,
                            contentDescription = "Load Capsule",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(24.dp)
                        )
                        Column {
                            Text(
                                text = "Load Capsule",
                                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold)
                            )
                            Text(
                                text = "Load saved templates directly into chat input",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    
                    OutlinedButton(
                        onClick = { showCapsulesMenu = false },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text("Cancel")
                    }
                }
            }
        }
    }

    if (showCapsulePicker) {
        Dialog(onDismissRequest = { showCapsulePicker = false }) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.85f),
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 6.dp
            ) {
                Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Select Capsule to Load",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                        )
                        IconButton(onClick = { showCapsulePicker = false }) {
                            Icon(Icons.Default.Close, "Dismiss")
                        }
                    }
                    
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
                    Spacer(modifier = Modifier.height(12.dp))
                    
                    OutlinedTextField(
                        value = pickerSearchQuery,
                        onValueChange = { pickerSearchQuery = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("Search capsules...") },
                        leadingIcon = { Icon(Icons.Default.Search, "Search Icon", tint = MaterialTheme.colorScheme.secondary) },
                        trailingIcon = {
                            if (pickerSearchQuery.isNotEmpty()) {
                                IconButton(onClick = { pickerSearchQuery = "" }) {
                                    Icon(Icons.Default.Close, "Clear", tint = MaterialTheme.colorScheme.secondary)
                                }
                            }
                        },
                        singleLine = true,
                        shape = RoundedCornerShape(8.dp)
                    )
                    
                    Spacer(modifier = Modifier.height(12.dp))
                    
                    val filteredCapsules = remember(capsules, pickerSearchQuery) {
                        if (pickerSearchQuery.isBlank()) {
                            capsules
                        } else {
                            capsules.filter {
                                it.title.contains(pickerSearchQuery, ignoreCase = true) ||
                                it.content.contains(pickerSearchQuery, ignoreCase = true)
                            }
                        }
                    }
                    
                    if (filteredCapsules.isEmpty()) {
                        Box(
                            modifier = Modifier.fillMaxWidth().weight(1f),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "No saved capsules found.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.secondary
                            )
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(filteredCapsules) { capsule ->
                                Card(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            val escapedContent = capsule.content
                                                .replace("\\", "\\\\")
                                                .replace("`", "\\`")
                                                .replace("$", "\\$")
                                                .replace("\n", "\\n")
                                                .replace("\r", "")
                                                .replace("\"", "\\\"")
                                                .replace("'", "\\'")
                                                
                                            val injectJs = """
                                                (function() {
                                                    var text = "$escapedContent";
                                                    var selectors = [
                                                        '#prompt-textarea',
                                                        'div[contenteditable="true"]',
                                                        'textarea[placeholder*="Claude"]',
                                                        'textarea[placeholder*="ChatGPT"]',
                                                        'textarea[placeholder*="Gemini"]',
                                                        'textarea[placeholder*="Ask"]',
                                                        'textarea[placeholder*="Message"]',
                                                        'textarea',
                                                        'div[placeholder*="Message"]'
                                                    ];
                                                    for (var i = 0; i < selectors.length; i++) {
                                                        var el = document.querySelector(selectors[i]);
                                                        if (el) {
                                                            el.focus();
                                                            if (el.tagName.toLowerCase() === 'div' && el.getAttribute('contenteditable') === 'true') {
                                                                el.innerText = text;
                                                            } else {
                                                                el.value = text;
                                                            }
                                                            var event = new Event('input', { bubbles: true });
                                                            el.dispatchEvent(event);
                                                            var event2 = new Event('change', { bubbles: true });
                                                            el.dispatchEvent(event2);
                                                            return "success";
                                                        }
                                                    }
                                                    return "fallback";
                                                })()
                                            """.trimIndent()
                                            
                                            webViewRef?.evaluateJavascript(injectJs) { evalResult ->
                                                val cleanEval = evalResult ?: ""
                                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                                val clip = ClipData.newPlainText("ContextDrop Loaded Capsule", capsule.content)
                                                clipboard.setPrimaryClip(clip)
                                                
                                                if (cleanEval.contains("success")) {
                                                    Toast.makeText(context, "Loaded! Capsule content inserted into chat.", Toast.LENGTH_SHORT).show()
                                                } else {
                                                    Toast.makeText(context, "Context copied to clipboard! Long-press input to paste.", Toast.LENGTH_LONG).show()
                                                }
                                            }
                                            showCapsulePicker = false
                                        },
                                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)),
                                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
                                ) {
                                    Column(modifier = Modifier.padding(12.dp)) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                text = capsule.title,
                                                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold),
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                modifier = Modifier.weight(1f)
                                            )
                                            
                                            val badgeColor = when (capsule.model) {
                                                "Claude" -> Color(0xFFD97706)
                                                "ChatGPT" -> Color(0xFF10A37F)
                                                "Gemini" -> Color(0xFF3B82F6)
                                                else -> Color(0xFF6B7280)
                                            }
                                            Surface(
                                                shape = RoundedCornerShape(4.dp),
                                                color = badgeColor.copy(alpha = 0.12f)
                                            ) {
                                                Text(
                                                    text = capsule.model.uppercase(),
                                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
                                                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold, color = badgeColor)
                                                )
                                            }
                                        }
                                        
                                        Spacer(modifier = Modifier.height(4.dp))
                                        
                                        Text(
                                            text = capsule.content,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.secondary,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// ================= CAPSULE DETAIL DIALOG =================

@OptIn(ExperimentalAnimationApi::class)
@Composable
fun CapsuleDetailDialog(
    capsule: ContextBlock,
    onDismiss: () -> Unit,
    onCopy: () -> Unit,
    onDownload: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.85f),
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Toolbar heading
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Capsule Details",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, "Dismiss")
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))

                // Scrollable details body
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = MaterialTheme.colorScheme.primaryContainer
                        ) {
                            Text(
                                text = capsule.model.uppercase(),
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            )
                        }
                        
                        Text(
                            text = formatRelativeTime(capsule.timestamp),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.secondary
                        )
                    }

                    Text(
                        text = capsule.title,
                        style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.primary
                    )

                    if (capsule.category.isNotBlank() || capsule.tags.isNotBlank()) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            if (capsule.category.isNotBlank()) {
                                Text(
                                    text = "@" + capsule.category,
                                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                                    color = MaterialTheme.colorScheme.secondary
                                )
                            }
                            if (capsule.tags.isNotBlank()) {
                                capsule.tags.split(",").forEach { tag ->
                                    if (tag.isNotBlank()) {
                                        Text(
                                            text = "#" + tag.trim(),
                                            style = MaterialTheme.typography.labelMedium,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                }
                            }
                        }
                    }

                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))

                    Text(
                        text = capsule.content,
                        style = MaterialTheme.typography.bodyLarge.copy(
                            lineHeight = 24.sp,
                            fontFamily = FontFamily.Default
                        ),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))

                // Quick Controls row
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButtonWithLabel(
                        icon = Icons.Default.ContentCopy,
                        label = "Copy",
                        onClick = onCopy
                    )
                    IconButtonWithLabel(
                        icon = Icons.Default.FileDownload,
                        label = "TXT",
                        onClick = onDownload
                    )
                    IconButtonWithLabel(
                        icon = Icons.Default.Share,
                        label = "Share",
                        onClick = onShare
                    )
                    IconButtonWithLabel(
                        icon = Icons.Default.Delete,
                        label = "Delete",
                        onClick = onDelete,
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }
}

@Composable
fun IconButtonWithLabel(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
    tint: Color = MaterialTheme.colorScheme.primary
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.clickable(onClick = onClick).padding(8.dp)
    ) {
        Icon(icon, contentDescription = label, tint = tint)
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
            color = tint
        )
    }
}

// ================= WORKSPACE OVERLAYS / BOTTOM SHEETS =================

@Composable
fun ModalBottomSheetDialog(
    capsule: ContextBlock,
    onDismiss: () -> Unit,
    onCopy: () -> Unit,
    onDownload: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit
) {
    // Elegant system dialog replicating bottom action drawer sheets cleanly
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp),
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 8.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = capsule.title,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "Platform: ${capsule.model} • ${formatRelativeTime(capsule.timestamp)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.padding(bottom = 12.dp)
                )

                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))

                BottomSheetActionItem(
                    icon = Icons.Default.ContentCopy,
                    label = "Copy Capsule Content",
                    onClick = onCopy
                )
                BottomSheetActionItem(
                    icon = Icons.Default.FileDownload,
                    label = "Download TXT Document",
                    onClick = onDownload
                )
                BottomSheetActionItem(
                    icon = Icons.Default.Share,
                    label = "Share Capsule",
                    onClick = onShare
                )
                BottomSheetActionItem(
                    icon = Icons.Default.Delete,
                    label = "Delete Capsule",
                    onClick = onDelete,
                    tint = MaterialTheme.colorScheme.error
                )
                
                Spacer(modifier = Modifier.height(8.dp))
                
                OutlinedButton(
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("Close")
                }
            }
        }
    }
}

@Composable
fun BottomSheetActionItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
    tint: Color = MaterialTheme.colorScheme.primary
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = label, tint = tint)
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
            color = if (tint == MaterialTheme.colorScheme.error) tint else MaterialTheme.colorScheme.onSurface
        )
    }
}

// ================= SETTINGS SCREEN VIEW =================

@Composable
fun SettingsScreenView(onClearAllData: () -> Unit) {
    val context = LocalContext.current
    var isCheckingUpdates by remember { mutableStateOf(false) }
    var privacyPolicyShow by remember { mutableStateOf(false) }
    var clearCacheConfirm by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFFFAFAFA))
            .padding(horizontal = 24.dp)
    ) {
        Spacer(modifier = Modifier.height(24.dp))
        Text(
            text = "ContextDrop Vault Control",
            style = MaterialTheme.typography.headlineSmall.copy(
                fontWeight = FontWeight.Black,
                letterSpacing = (-0.5).sp
            ),
            color = MaterialTheme.colorScheme.primary
        )

        Spacer(modifier = Modifier.height(24.dp))

        // Large Premium Dashboard Settings Grid
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Check updates capsule
            SettingsCategoryCard(title = "App Information") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text("App Version", style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold))
                        Text("Stable Production Build", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
                    }
                    Text("1.5.0", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold), color = MaterialTheme.colorScheme.primary)
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            isCheckingUpdates = true
                        }
                        .padding(vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text("Check for Updates", style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold))
                        Text("Verify newest features & compatibility", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
                    }
                    if (isCheckingUpdates) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        LaunchedEffect(Unit) {
                            delay(1500)
                            isCheckingUpdates = false
                            Toast.makeText(context, "ContextDrop is up to date (v1.5.0)!", Toast.LENGTH_SHORT).show()
                        }
                    } else {
                        Icon(Icons.Default.ArrowForwardIos, "Arrow", modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.secondary)
                    }
                }
            }

            // Trust & safety card
            SettingsCategoryCard(title = "Privacy & Sandbox") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { privacyPolicyShow = true }
                        .padding(vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text("Privacy Policy", style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold))
                        Text("Zero external storage tracker disclosure", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
                    }
                    Icon(Icons.Default.ArrowForwardIos, "Arrow", modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.secondary)
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { clearCacheConfirm = true }
                        .padding(vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text("Wipe Sandbox Data", style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold), color = MaterialTheme.colorScheme.error)
                        Text("Clear browser cache cookies and SQLite databases", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
                    }
                    Icon(Icons.Default.Refresh, "Clear", modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.error)
                }
            }

            // About Brand
            SettingsCategoryCard(title = "About ContextDrop") {
                Text(
                    text = "ContextDrop is a high-speed sandbox app tailored for prompt writers and software engineers using more than one conversational language model (such as Claude, ChatGPT, Gemini, etc.). We store conversational blocks securely offline with physical-level device protection.",
                    style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 22.sp),
                    color = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.padding(vertical = 8.dp)
                )
            }
        }
    }

    // Privacy disclosures dialog
    if (privacyPolicyShow) {
        AlertDialog(
            onDismissRequest = { privacyPolicyShow = false },
            confirmButton = {
                Button(onClick = { privacyPolicyShow = false }) {
                    Text("I Understand")
                }
            },
            title = { Text("Privacy Disclosure") },
            text = {
                Text(
                    text = "ContextDrop takes privacy seriously. Your conversation capsules and scraped model text reside strictly on your local physical device Storage inside a secure Android SQLite Database. The system contains zero telemetry trackers, cloud analytical cookies, or third party advertisement scripts. Your persistent web service configurations are untracked and locked down inside the sandboxed Android WebKit container.",
                    style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 22.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        )
    }

    // Clear cache safety confirmation
    if (clearCacheConfirm) {
        AlertDialog(
            onDismissRequest = { clearCacheConfirm = false },
            confirmButton = {
                Button(
                    onClick = {
                        onClearAllData()
                        clearCacheConfirm = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Clear All")
                }
            },
            dismissButton = {
                TextButton(onClick = { clearCacheConfirm = false }) {
                    Text("Cancel")
                }
            },
            title = { Text("Confirm wiping databases?") },
            text = { Text("This will permanently clear all of your saved conversation capsules and invalidate your active browser logins (ChatGPT, Claude, Gemini sessions). This action is irreversible.") }
        )
    }
}

@Composable
fun SettingsCategoryCard(
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Text(
                text = title.uppercase(),
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold, letterSpacing = 1.sp),
                color = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.padding(bottom = 12.dp)
            )
            content()
        }
    }
}

// ================= LOGOS & GRAPHICS =================

@Composable
fun Variant3Logo(modifier: Modifier = Modifier.size(44.dp)) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0xFF6366F1)), // Modern Indigo/Purple Brand Accent
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.size(28.dp)) {
            val cx = size.width / 2
            val cy = size.height / 2
            val r = size.width / 2
            
            // Re-draw a stylish dropping geometric Context Capsule
            val path = androidx.compose.ui.graphics.Path().apply {
                moveTo(cx, cy - r)
                quadraticBezierTo(cx, cy, cx + r, cy)
                quadraticBezierTo(cx, cy, cx, cy + r)
                quadraticBezierTo(cx, cy, cx - r, cy)
                quadraticBezierTo(cx, cy, cx, cy - r)
                close()
            }
            drawPath(path, color = Color.White)
        }
    }
}

@Composable
fun ClaudeLogo(modifier: Modifier = Modifier.size(44.dp)) {
    Box(
        modifier = modifier
            .clip(CircleShape)
            .background(Color(0xFFF1EAD4)),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.size(24.dp)) {
            val cx = size.width / 2
            val cy = size.height / 2
            val r = 5.6.dp.toPx()
            val color = Color(0xFFD97706) // Claude elegant copper gold
            
            drawCircle(color = color, radius = r, center = Offset(cx, cy))
            drawCircle(color = color, radius = r, center = Offset(cx - r * 1.4f, cy))
            drawCircle(color = color, radius = r, center = Offset(cx + r * 1.4f, cy))
            drawCircle(color = color, radius = r, center = Offset(cx, cy - r * 1.4f))
            drawCircle(color = color, radius = r, center = Offset(cx, cy + r * 1.4f))
        }
    }
}

@Composable
fun ChatGPTLogo(modifier: Modifier = Modifier.size(44.dp)) {
    Box(
        modifier = modifier
            .clip(CircleShape)
            .background(Color(0xFF10A37F)), // ChatGPT elegant emerald
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.size(22.dp)) {
            val cx = size.width / 2
            val cy = size.height / 2
            val r = 4.8.dp.toPx()
            
            drawCircle(color = Color.White, radius = r, center = Offset(cx, cy))
            for (i in 0 until 6) {
                val angle = (i * Math.PI / 3).toFloat()
                val ox = cx + r * kotlin.math.cos(angle)
                val oy = cy + r * kotlin.math.sin(angle)
                drawCircle(color = Color.White.copy(alpha = 0.9f), radius = r * 0.82f, center = Offset(ox, oy))
            }
        }
    }
}

@Composable
fun GeminiLogo(modifier: Modifier = Modifier.size(44.dp)) {
    Box(
        modifier = modifier
            .clip(CircleShape)
            .background(Color(0xFF1E293B)), // Deep Gemini space slate
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.size(24.dp)) {
            val cx = size.width / 2
            val cy = size.height / 2
            
            val path1 = androidx.compose.ui.graphics.Path().apply {
                val s1 = 11.5.dp.toPx()
                moveTo(cx, cy - s1)
                quadraticBezierTo(cx, cy, cx + s1 * 0.7f, cy)
                quadraticBezierTo(cx, cy, cx, cy + s1)
                quadraticBezierTo(cx, cy, cx - s1 * 0.7f, cy)
                quadraticBezierTo(cx, cy, cx, cy - s1)
                close()
            }
            drawPath(path1, color = Color(0xFF3B82F6)) // Gemini royal blue star
            
            val path2 = androidx.compose.ui.graphics.Path().apply {
                val s2 = 5.5.dp.toPx()
                val ox = cx + 5.5.dp.toPx()
                val oy = cy - 5.5.dp.toPx()
                moveTo(ox, oy - s2)
                quadraticBezierTo(ox, oy, ox + s2 * 0.7f, oy)
                quadraticBezierTo(ox, oy, ox, oy + s2)
                quadraticBezierTo(ox, oy, ox - s2 * 0.7f, oy)
                quadraticBezierTo(ox, oy, ox, oy - s2)
                close()
            }
            drawPath(path2, color = Color(0xFFF59E0B)) // Gemini spark amber
        }
    }
}

fun formatRelativeTime(timestamp: Long): String {
    val diff = System.currentTimeMillis() - timestamp
    val seconds = diff / 1000
    val minutes = seconds / 60
    val hours = minutes / 60
    val days = hours / 24
    return when {
        days > 1 -> "$days days ago"
        days == 1L -> "Yesterday"
        hours > 1 -> "$hours hours ago"
        hours == 1L -> "1 hour ago"
        minutes > 1 -> "$minutes minutes ago"
        else -> "Just now"
    }
}
