package com.example

import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.FolderZip
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.ViewModelProvider
import com.example.data.Capsule
import com.example.data.CapsuleDatabase
import com.example.data.CapsuleRepository
import com.example.ui.CapsuleViewModel
import com.example.ui.CapsuleViewModelFactory
import com.example.ui.Screen
import com.example.ui.theme.MyApplicationTheme
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : ComponentActivity() {

    private lateinit var viewModel: CapsuleViewModel
    private val webViews = mutableMapOf<String, WebView>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Setup Room and Repository
        val database = CapsuleDatabase.getDatabase(this)
        val repository = CapsuleRepository(database.capsuleDao())
        
        // Initialize ViewModel via Factory
        val factory = CapsuleViewModelFactory(repository)
        viewModel = ViewModelProvider(this, factory)[CapsuleViewModel::class.java]

        enableEdgeToEdge()
        
        setContent {
            MyApplicationTheme {
                MainLayout()
            }
        }
    }

    // Helper to request or recycle a WebView for the particular AI
    fun getOrCreateWebView(aiName: String, initialUrl: String): WebView {
        return webViews.getOrPut(aiName) {
            WebView(this).apply {
                layoutParams = android.view.ViewGroup.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT
                )
                settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    databaseEnabled = true
                    allowFileAccess = true
                    useWideViewPort = true
                    loadWithOverviewMode = true
                    // Modern user agent to bypass ChatGPT / Claude compatibility blocks
                    userAgentString = "Mozilla/5.0 (Linux; Android 13; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Mobile Safari/537.36"
                }
                
                // Keep cookies persistent
                CookieManager.getInstance().setAcceptCookie(true)
                CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                
                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)
                        CookieManager.getInstance().flush()
                    }
                }
                webChromeClient = WebChromeClient()
                loadUrl(initialUrl)
            }
        }
    }

    // JavaScript code specialized per AI model to fetch structured chats with safe general backup
    private fun getJsSelectorForAi(aiName: String): String {
        return when (aiName) {
            "ChatGPT" -> """
                (function() {
                    var elements = document.querySelectorAll('div[data-message-author-role]');
                    var textParts = [];
                    elements.forEach(function(el) {
                        var role = el.getAttribute('data-message-author-role');
                        var txt = el.innerText || el.textContent;
                        if (txt && txt.trim().length > 0) {
                            textParts.push("[" + role.toUpperCase() + "]:\n" + txt.trim());
                        }
                    });
                    
                    if (textParts.length === 0) {
                        var pElements = document.querySelectorAll('p, pre');
                        var accumulated = "";
                        pElements.forEach(function(el) {
                            var inner = el.innerText || el.textContent;
                            if (inner && inner.length > 10) accumulated += inner + "\n\n";
                        });
                        return accumulated.trim();
                    }
                    return textParts.join("\n\n");
                })()
            """.trimIndent()
            "Claude" -> """
                (function() {
                    var textParts = [];
                    var turns = document.querySelectorAll('[class*="human-turn"], [class*="assistant-turn"]');
                    if (turns.length > 0) {
                        turns.forEach(function(el) {
                            var isHuman = el.className.indexOf('human-turn') !== -1;
                            var role = isHuman ? 'HUMAN' : 'CLAUDE';
                            var txt = el.innerText || el.textContent;
                            if (txt && txt.trim().length > 0) {
                                textParts.push("[" + role + "]:\n" + txt.trim());
                            }
                        });
                    }
                    
                    if (textParts.length === 0) {
                        var elements = document.querySelectorAll('*');
                        elements.forEach(function(el) {
                            var className = el.className;
                            if (typeof className === 'string') {
                                if (className.indexOf('human-turn') !== -1) {
                                    textParts.push("[HUMAN]:\n" + el.innerText.trim());
                                } else if (className.indexOf('assistant-turn') !== -1) {
                                    textParts.push("[CLAUDE]:\n" + el.innerText.trim());
                                }
                            }
                        });
                    }
                    
                    if (textParts.length === 0) {
                        var pElements = document.querySelectorAll('p, pre');
                        var accumulated = "";
                        pElements.forEach(function(el) {
                            var inner = el.innerText || el.textContent;
                            if (inner && inner.length > 10) accumulated += inner + "\n\n";
                        });
                        return accumulated.trim();
                    }
                    return textParts.join("\n\n");
                })()
            """.trimIndent()
            "Gemini" -> """
                (function() {
                    var elements = document.querySelectorAll('message-content, .message-content, [class*="message-content"]');
                    var textParts = [];
                    elements.forEach(function(el, idx) {
                        var mText = el.innerText || el.textContent;
                        if (mText && mText.trim().length > 0) {
                            var role = (idx % 2 === 0) ? 'USER' : 'GEMINI';
                            textParts.push("[" + role + "]:\n" + mText.trim());
                        }
                    });
                    
                    if (textParts.length === 0) {
                        var pElements = document.querySelectorAll('p, pre');
                        var accumulated = "";
                        pElements.forEach(function(el) {
                            var inner = el.innerText || el.textContent;
                            if (inner && inner.length > 10) accumulated += inner + "\n\n";
                        });
                        return accumulated.trim();
                    }
                    return textParts.join("\n\n");
                })()
            """.trimIndent()
            else -> ""
        }
    }

    // JSON string clean unicode/unescaper
    private fun unescapeJsonString(jsonStr: String): String {
        if (jsonStr == "null" || jsonStr.isEmpty()) return ""
        var s = if (jsonStr.startsWith("\"") && jsonStr.endsWith("\"") && jsonStr.length >= 2) {
            jsonStr.substring(1, jsonStr.length - 1)
        } else {
            jsonStr
        }
        s = s.replace("\\\\", "\\")
            .replace("\\\"", "\"")
            .replace("\\n", "\n")
            .replace("\\r", "\r")
            .replace("\\t", "\t")
            .replace("\\b", "\u0008")
            .replace("\\f", "\u000c")
        val regex = Regex("\\\\u([0-9a-fA-F]{4})")
        return regex.replace(s) { matchResult ->
            matchResult.groupValues[1].toInt(16).toChar().toString()
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    fun MainLayout() {
        val currentScreen by viewModel.currentScreen
        val capsulesList by viewModel.capsules.collectAsState()
        val context = LocalContext.current
        val clipboardManager = LocalClipboardManager.current

        // Handle Backpress based on current active state
        when (currentScreen) {
            is Screen.WebViewScreen -> {
                val screenState = currentScreen as Screen.WebViewScreen
                val activeWebView = getOrCreateWebView(screenState.aiName, screenState.url)
                BackHandler {
                    if (activeWebView.canGoBack()) {
                        activeWebView.goBack()
                    } else {
                        viewModel.navigateTo(Screen.Home)
                    }
                }
            }
            Screen.Library -> {
                BackHandler {
                    viewModel.navigateTo(Screen.Home)
                }
            }
            Screen.Home -> {
                // Let system handle home back exit
            }
        }

        Scaffold { innerPadding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                when (currentScreen) {
                    Screen.Home -> HomeScreen()
                    is Screen.WebViewScreen -> {
                        val state = currentScreen as Screen.WebViewScreen
                        WebViewContainer(aiName = state.aiName, url = state.url)
                    }
                    Screen.Library -> LibraryScreen()
                }

                // Dialog: Save Capsule Prompt
                if (viewModel.showSaveDialog.value) {
                    var currentInput by viewModel.capsuleNameInput
                    AlertDialog(
                        onDismissRequest = { viewModel.showSaveDialog.value = false },
                        title = {
                            Text(
                                "Save Capsule Context",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                        },
                        text = {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(
                                    "Enter a descriptive name for this extracted chat conversation context.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                OutlinedTextField(
                                    value = currentInput,
                                    onValueChange = { currentInput = it },
                                    label = { Text("Capsule Title") },
                                    singleLine = true,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("capsule_title_input"),
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                                        unfocusedBorderColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                                    )
                                )
                            }
                        },
                        confirmButton = {
                            Button(
                                onClick = {
                                    if (currentInput.trim().isNotEmpty()) {
                                        viewModel.saveCapsule()
                                        Toast.makeText(context, "Capsule saved successfully!", Toast.LENGTH_SHORT).show()
                                    } else {
                                        Toast.makeText(context, "Name cannot be empty!", Toast.LENGTH_SHORT).show()
                                    }
                                },
                                modifier = Modifier.testTag("save_dialog_confirm_button")
                            ) {
                                Text("Save")
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = { viewModel.showSaveDialog.value = false }) {
                                Text("Cancel")
                            }
                        }
                    )
                }

                // Dialog: Capsule Detail Options (Copy, View, Delete)
                viewModel.selectedCapsuleForDetail.value?.let { capsule ->
                    AlertDialog(
                        onDismissRequest = { viewModel.selectedCapsuleForDetail.value = null },
                        title = {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    capsule.name,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.weight(1f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                // Quick category indicator
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = getAiColor(capsule.aiName).copy(alpha = 0.2f)
                                ) {
                                    Text(
                                        text = capsule.aiName,
                                        color = getAiColor(capsule.aiName),
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        },
                        text = {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 280.dp)
                            ) {
                                Text(
                                    text = SimpleDateFormat("MMM dd, yyyy 'at' hh:mm a", Locale.getDefault()).format(Date(capsule.timestamp)),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(modifier = Modifier.height(12.dp))
                                Surface(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .weight(1f),
                                    color = MaterialTheme.colorScheme.surfaceVariant,
                                    shape = RoundedCornerShape(8.dp),
                                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.15f))
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .verticalScroll(rememberScrollState())
                                            .padding(12.dp)
                                    ) {
                                        Text(
                                            text = capsule.content,
                                            style = MaterialTheme.typography.bodySmall,
                                            fontFamily = FontFamily.Monospace,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                    }
                                }
                            }
                        },
                        confirmButton = {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                // Delete Option
                                TextButton(
                                    onClick = {
                                        viewModel.deleteCapsule(capsule.id)
                                        Toast.makeText(context, "Capsule deleted", Toast.LENGTH_SHORT).show()
                                    },
                                    modifier = Modifier.testTag("detail_dialog_delete_button"),
                                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                                ) {
                                    Icon(Icons.Outlined.Delete, contentDescription = "Delete", modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Delete")
                                }
                                
                                Spacer(modifier = Modifier.weight(1f))

                                // Copy Option
                                Button(
                                    onClick = {
                                        clipboardManager.setText(AnnotatedString(capsule.content))
                                        Toast.makeText(context, "Full conversation copied to clipboard", Toast.LENGTH_SHORT).show()
                                    },
                                    modifier = Modifier.testTag("detail_dialog_copy_button")
                                ) {
                                    Icon(Icons.Outlined.ContentCopy, contentDescription = "Copy", modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Copy")
                                }
                            }
                        }
                    )
                }
            }
        }
    }

    @Composable
    fun HomeScreen() {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(24.dp))
            
            // App Title Logo & Header
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
                modifier = Modifier.fillMaxWidth()
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(
                            Brush.linearGradient(
                                colors = listOf(Color(0xFF60A5FA), Color(0xFFC084FC))
                            )
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Filled.FolderZip,
                        contentDescription = "CapsuleX Logo",
                        tint = Color.White,
                        modifier = Modifier.size(24.dp)
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = "CapsuleX",
                    style = MaterialTheme.typography.headlineLarge,
                    fontWeight = FontWeight.ExtraBold,
                    color = Color.White
                )
            }
            
            Text(
                text = "AI Conversation Context Archiver",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                modifier = Modifier.padding(top = 4.dp, bottom = 28.dp)
            )

            // Direct button to stored database state
            Button(
                onClick = { viewModel.navigateTo(Screen.Library) },
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("view_library_home_button")
            ) {
                Icon(Icons.Filled.FolderOpen, contentDescription = "Folder", tint = MaterialTheme.colorScheme.primary)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Open Stored context Library", color = MaterialTheme.colorScheme.onSurface)
            }

            Spacer(modifier = Modifier.height(24.dp))
            
            Text(
                text = "Select active AI Web sandbox",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .align(Alignment.Start)
                    .padding(bottom = 12.dp)
            )

            // Claude Card
            AiPortalCard(
                name = "Claude",
                url = "https://claude.ai",
                description = "Anthropic's helpful assistant. Great for highly conceptual context extraction.",
                accentColor = Color(0xFFD97753),
                logo = { ClaudeLogo() }
            )

            Spacer(modifier = Modifier.height(16.dp))

            // ChatGPT Card
            AiPortalCard(
                name = "ChatGPT",
                url = "https://chatgpt.com",
                description = "OpenAI's smart companion. Instantly extract threads using structured author roles.",
                accentColor = Color(0xFF10A37F),
                logo = { ChatGptLogo() }
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Gemini Card
            AiPortalCard(
                name = "Gemini",
                url = "https://gemini.google.com",
                description = "Google's powerful model. Ideal for rich canvas and message blocks archivers.",
                accentColor = Color(0xFF60A5FA),
                logo = { GeminiLogo() }
            )
            
            Spacer(modifier = Modifier.height(40.dp))
        }
    }

    @Composable
    fun AiPortalCard(
        name: String,
        url: String,
        description: String,
        accentColor: Color,
        logo: @Composable () -> Unit
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { viewModel.navigateTo(Screen.WebViewScreen(name, url)) }
                .testTag("ai_card_${name.lowercase()}"),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                logo()
                
                Spacer(modifier = Modifier.width(16.dp))
                
                Column(modifier = Modifier.weight(1.1f)) {
                    Text(
                        text = name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Spacer(modifier = Modifier.width(8.dp))

                IconButton(
                    onClick = { viewModel.navigateTo(Screen.WebViewScreen(name, url)) },
                    colors = IconButtonDefaults.iconButtonColors(containerColor = accentColor.copy(alpha = 0.15f))
                ) {
                    Icon(
                        imageVector = Icons.Filled.ArrowForwardIos,
                        contentDescription = "Open $name",
                        tint = accentColor,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }
    }

    @Composable
    fun WebViewContainer(aiName: String, url: String) {
        Column(modifier = Modifier.fillMaxSize()) {
            
            // Web Header
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.background, // Elegant Dark Background #1C1B1F
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline), // Solid Slate Border #49454F
                shadowElevation = 0.dp
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = { viewModel.navigateTo(Screen.Home) },
                        modifier = Modifier.testTag("webview_back_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back to home",
                            tint = MaterialTheme.colorScheme.onBackground
                        )
                    }
                    
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .padding(start = 8.dp)
                    ) {
                        Text(
                            text = aiName,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                        Text(
                            text = when (aiName) {
                                "ChatGPT" -> "chatgpt.com"
                                "Claude" -> "claude.ai"
                                "Gemini" -> "gemini.google.com"
                                else -> ""
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    // Compact Status Indicator
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(Color(0xFF4ADE80)) // Active light green
                        )
                        Text(
                            text = "ACTIVE",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            letterSpacing = 1.sp
                        )
                    }
                }
            }

            // Central WebView box
            Box(
                modifier = Modifier
                    .weight(1f)
                    .background(MaterialTheme.colorScheme.background)
            ) {
                AndroidView(
                    factory = { getOrCreateWebView(aiName, url) },
                    modifier = Modifier
                        .fillMaxSize()
                        .testTag("webview_$aiName")
                )
            }

            // FIXED Bottom native bar (Material 3 style)
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("webview_bottom_navigation_bar"),
                color = MaterialTheme.colorScheme.surface, // Elegant Dark Surface #2B2930
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline), // Top border divider #49454F
                shadowElevation = 8.dp
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    
                    // Button 1: Save Capsule (Violet highlight accent #D0BCFF, dark text #381E72)
                    Button(
                        onClick = {
                            val activeWebView = getOrCreateWebView(aiName, url)
                            val filterJs = getJsSelectorForAi(aiName)
                            
                            activeWebView.evaluateJavascript(filterJs) { result ->
                                val cleanText = unescapeJsonString(result ?: "")
                                if (cleanText.trim().isEmpty() || cleanText == "null") {
                                    Toast.makeText(
                                        this@MainActivity,
                                        "No structural chat matches on screen yet. Start a prompt first!",
                                        Toast.LENGTH_LONG
                                    ).show()
                                } else {
                                    viewModel.openSaveDialog(aiName, cleanText)
                                }
                            }
                        },
                        modifier = Modifier
                            .weight(1.1f)
                            .height(48.dp)
                            .testTag("save_capsule_button"),
                        shape = RoundedCornerShape(100.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary, // #D0BCFF
                            contentColor = MaterialTheme.colorScheme.onPrimary  // #381E72
                        ),
                        elevation = ButtonDefaults.buttonElevation(defaultElevation = 2.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Backup,
                            contentDescription = "Save Capsule Icon",
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Save Capsule", 
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.labelLarge
                        )
                    }

                    // Button 2: Library (Outline border #49454F)
                    OutlinedButton(
                        onClick = { viewModel.navigateTo(Screen.Library) },
                        modifier = Modifier
                            .weight(0.9f)
                            .height(48.dp)
                            .testTag("library_navigation_button"),
                        shape = RoundedCornerShape(100.dp),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    ) {
                        Icon(
                            imageVector = Icons.Filled.FolderOpen, 
                            contentDescription = "Library Icon", 
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Library", 
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.labelLarge
                        )
                    }
                }
            }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    fun LibraryScreen() {
        val list by viewModel.capsules.collectAsState()
        var searchInput by remember { mutableStateOf("") }

        val filteredList = remember(list, searchInput) {
            if (searchInput.trim().isEmpty()) {
                list
            } else {
                list.filter {
                    it.name.contains(searchInput, ignoreCase = true) ||
                    it.aiName.contains(searchInput, ignoreCase = true) ||
                    it.content.contains(searchInput, ignoreCase = true)
                }
            }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
        ) {
            // Main Top app bar back trigger
            TopAppBar(
                title = { Text("Capsule Library", fontWeight = FontWeight.Bold, color = Color.White) },
                navigationIcon = {
                    IconButton(
                        onClick = { viewModel.navigateTo(Screen.Home) },
                        modifier = Modifier.testTag("library_back_button")
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Go back", tint = Color.White)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
            )

            // Search Header View
            OutlinedTextField(
                value = searchInput,
                onValueChange = { searchInput = it },
                placeholder = { Text("Search stored capsules...") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = "Search icon") },
                trailingIcon = {
                    if (searchInput.isNotEmpty()) {
                        IconButton(onClick = { searchInput = "" }) {
                            Icon(Icons.Filled.Close, contentDescription = "Clear search")
                        }
                    }
                },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp)
                    .testTag("library_search_field"),
                shape = RoundedCornerShape(12.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
                )
            )

            // Dynamic lazy body of items
            if (filteredList.isEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = Icons.Filled.Inbox,
                        contentDescription = "Empty box",
                        modifier = Modifier.size(56.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = if (searchInput.isNotEmpty()) "No matches found" else "Your Capsule Library is empty",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = if (searchInput.isNotEmpty()) "Try searching different words." else "Open a Web Sandbox screen to save real capsule threads.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentPadding = PaddingValues(bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(filteredList, key = { it.id }) { capsule ->
                        LibraryCapsuleCard(capsule = capsule)
                    }
                }
            }
        }
    }

    @Composable
    fun LibraryCapsuleCard(capsule: Capsule) {
        val context = LocalContext.current
        val dateString = remember(capsule.timestamp) {
            SimpleDateFormat("MMM d, yyyy 'at' h:mm a", Locale.getDefault()).format(Date(capsule.timestamp))
        }

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .clickable { viewModel.selectedCapsuleForDetail.value = capsule }
                .testTag("capsule_card_${capsule.id}"),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Title
                    Text(
                        text = capsule.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    // Tag label
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = getAiColor(capsule.aiName).copy(alpha = 0.15f)
                    ) {
                        Text(
                            text = capsule.aiName,
                            color = getAiColor(capsule.aiName),
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))

                // Date stamp
                Text(
                    text = dateString,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                )

                Spacer(modifier = Modifier.height(10.dp))

                // Text excerpt
                Text(
                    text = capsule.content,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    fontFamily = FontFamily.Monospace
                )
            }
        }
    }

    private fun getAiColor(ai: String): Color {
        return when (ai) {
            "ChatGPT" -> Color(0xFF10A37F)
            "Claude" -> Color(0xFFD97753)
            "Gemini" -> Color(0xFF60A5FA)
            else -> Color(0xFF94A3B8)
        }
    }

    // Modern native SVG drawings for ChatGPT, Claude, and Gemini
    @Composable
    fun ChatGptLogo(modifier: Modifier = Modifier) {
        Box(
            modifier = modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(Color(0xFF10A37F)),
            contentAlignment = Alignment.Center
        ) {
            // Stylized minimalist intersecting gears to draw structural ChatGPT geometry
            Canvas(modifier = Modifier.size(24.dp)) {
                val r = size.minDimension / 4.5f
                val ctr = Offset(size.width / 2f, size.height / 2f)
                for (i in 0 until 6) {
                    val angle = (i * 60) * (Math.PI / 180.0)
                    val loc = Offset(
                        (ctr.x + r * Math.cos(angle)).toFloat(),
                        (ctr.y + r * Math.sin(angle)).toFloat()
                    )
                    drawCircle(
                        color = Color.White.copy(alpha = 0.8f),
                        radius = r,
                        center = loc,
                        style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.dp.toPx())
                    )
                }
            }
        }
    }

    @Composable
    fun ClaudeLogo(modifier: Modifier = Modifier) {
        Box(
            modifier = modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(Color(0xFFCC5A37)),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "C",
                color = Color.White,
                fontWeight = FontWeight.ExtraBold,
                fontSize = 24.sp,
                fontFamily = FontFamily.Serif
            )
        }
    }

    @Composable
    fun GeminiLogo(modifier: Modifier = Modifier) {
        Box(
            modifier = modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(
                    Brush.radialGradient(
                        colors = listOf(
                            Color(0xFF9966FF),
                            Color(0xFF3399FF)
                        )
                    )
                ),
            contentAlignment = Alignment.Center
        ) {
            Canvas(modifier = Modifier.size(26.dp)) {
                val path = Path().apply {
                    val w = size.width
                    val h = size.height
                    moveTo(w / 2f, 0f)
                    // Cubic Bezier curve creates Google Gemini four-pointed spark brilliantly
                    cubicAsBezier(w / 2f, 0f, w / 2f, h / 2f, w, h / 2f)
                    cubicAsBezier(w, h / 2f, w / 2f, h / 2f, w / 2f, h)
                    cubicAsBezier(w / 2f, h, w / 2f, h / 2f, 0f, h / 2f)
                    cubicAsBezier(0f, h / 2f, w / 2f, h / 2f, w / 2f, 0f)
                }
                drawPath(path, color = Color.White)
            }
        }
    }

    // Helper to draw clean cubic paths for custom Gemini sparks
    private fun Path.cubicAsBezier(x1: Float, y1: Float, ctrlX: Float, ctrlY: Float, x2: Float, y2: Float) {
        cubicTo(x1, y1, ctrlX, ctrlY, x2, y2)
    }
}
