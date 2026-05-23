package com.example

import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.content.ContentValues
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.webkit.WebViewClient
import android.webkit.RenderProcessGoneDetail
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.graphics.graphicsLayer
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
    private var isActivityDestroyed = false

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

    override fun onDestroy() {
        isActivityDestroyed = true
        webViews.values.toList().forEach { webView ->
            try {
                webView.clearHistory()
                webView.clearCache(true)
                webView.loadUrl("about:blank")
                (webView.parent as? android.view.ViewGroup)?.removeView(webView)
                webView.removeAllViews()
                // Do not explicitly call webView.destroy() to prevent native segfault crashes
                // on the renderer process during Compose teardown when onRelease is triggered.
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        webViews.clear()
        super.onDestroy()
    }

    override fun onPause() {
        super.onPause()
        if (!isActivityDestroyed) {
            webViews.values.toList().forEach { webView ->
                try {
                    webView.onPause()
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (!isActivityDestroyed) {
            webViews.values.toList().forEach { webView ->
                try {
                    webView.onResume()
                } catch (e: Exception) {
                    e.printStackTrace()
                }
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

                    override fun onRenderProcessGone(
                        view: WebView?,
                        detail: RenderProcessGoneDetail?
                    ): Boolean {
                        try {
                            if (view != null) {
                                val parent = view.parent as? ViewGroup
                                parent?.removeView(view)
                                view.destroy()
                            }
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                        
                        try {
                            val keyToRemove = webViews.filterValues { it === view }.keys.firstOrNull()
                            if (keyToRemove != null) {
                                webViews.remove(keyToRemove)
                            }
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }

                        runOnUiThread {
                            Toast.makeText(
                                this@MainActivity,
                                "AI Sandbox render process crashed. Safely recovered!",
                                Toast.LENGTH_SHORT
                             ).show()
                             viewModel.navigateTo(Screen.Home)
                        }
                        return true
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

        // Animated Splash Screen States
        var showSplash by remember { mutableStateOf(true) }
        var splashAlpha by remember { mutableStateOf(1f) }

        LaunchedEffect(Unit) {
            // Animate splash screen (600-800ms) and fade out
            kotlinx.coroutines.delay(550)
            val steps = 10
            for (i in 1..steps) {
                kotlinx.coroutines.delay(15)
                splashAlpha = (steps - i) / steps.toFloat()
            }
            showSplash = false
        }

        // Handle Backpress based on current active state
        when (currentScreen) {
            is Screen.WebViewScreen -> {
                val screenState = currentScreen as Screen.WebViewScreen
                BackHandler {
                    val activeWebView = webViews[screenState.aiName]
                    if (activeWebView != null && activeWebView.canGoBack()) {
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

                // Overlay animated splash screen if active
                if (showSplash) {
                    SplashScreen(alpha = splashAlpha)
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
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF18181B)
                            )
                        },
                        text = {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(
                                    "Enter a descriptive name for this extracted chat conversation context.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = Color(0xFF71717A)
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
                                        focusedBorderColor = Color(0xFF6E56CF),
                                        unfocusedBorderColor = Color(0xFFE4E4E7)
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
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF6E56CF)),
                                modifier = Modifier.testTag("save_dialog_confirm_button")
                            ) {
                                Text("Save", color = Color.White)
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = { viewModel.showSaveDialog.value = false }) {
                                Text("Cancel", color = Color(0xFF71717A))
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
                                    color = Color(0xFF18181B),
                                    modifier = Modifier.weight(1f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                // Quick category indicator
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = getAiColor(capsule.aiName).copy(alpha = 0.12f)
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
                                    color = Color(0xFF71717A)
                                )
                                Spacer(modifier = Modifier.height(12.dp))
                                Surface(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .weight(1f),
                                    color = Color(0xFFF4F4F5),
                                    shape = RoundedCornerShape(8.dp),
                                    border = BorderStroke(1.dp, Color(0xFFE4E4E7))
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
                                            color = Color(0xFF18181B)
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

                                // Download Option
                                OutlinedButton(
                                    onClick = {
                                        saveCapsuleToDownloads(context, capsule.name, capsule.content)
                                    },
                                    modifier = Modifier.testTag("detail_dialog_download_button"),
                                    border = BorderStroke(1.dp, Color(0xFFE4E4E7)),
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF71717A))
                                ) {
                                    Icon(Icons.Default.Download, contentDescription = "Download", modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Download")
                                }

                                // Copy Option
                                Button(
                                    onClick = {
                                        clipboardManager.setText(AnnotatedString(capsule.content))
                                        Toast.makeText(context, "Full conversation copied to clipboard", Toast.LENGTH_SHORT).show()
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF6E56CF)),
                                    modifier = Modifier.testTag("detail_dialog_copy_button")
                                ) {
                                    Icon(Icons.Outlined.ContentCopy, contentDescription = "Copy", modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Copy", color = Color.White)
                                }
                            }
                        }
                    )
                }
            }
        }
    }

    @Composable
    fun SplashScreen(alpha: Float) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.White)
                .graphicsLayer(alpha = alpha),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                ContextDropLogo(modifier = Modifier.size(96.dp))
                Spacer(modifier = Modifier.height(20.dp))
                Text(
                    text = "ContextDrop",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.ExtraBold,
                    color = Color(0xFF09090B),
                    letterSpacing = (-0.5).sp
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "Save once. Use anywhere.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color(0xFF71717A),
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }

    @Composable
    fun HomeScreen() {
        val list by viewModel.capsules.collectAsState()
        val context = LocalContext.current
        var searchQuery by remember { mutableStateOf("") }
        var showNewCapsulePromptChoice by remember { mutableStateOf(false) }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.White)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(12.dp))
            
            // App Title Logo & Header
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ContextDropLogo(modifier = Modifier.size(44.dp))
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = "ContextDrop",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.ExtraBold,
                            color = Color(0xFF09090B),
                            letterSpacing = (-0.5).sp
                        )
                        Text(
                            text = "Save once. Use anywhere.",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFF71717A)
                        )
                    }
                }
                
                IconButton(
                    onClick = {
                        Toast.makeText(context, "No new notifications", Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier.testTag("home_notification_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.Notifications,
                        contentDescription = "Notifications",
                        tint = Color(0xFF71717A),
                        modifier = Modifier.size(22.dp)
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(20.dp))

            // Search Filter Row
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text("Search capsules...", color = Color(0xFF94A3B8)) },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = "Search",
                        tint = Color(0xFF94A3B8)
                    )
                },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { searchQuery = "" }) {
                            Icon(Icons.Default.Close, contentDescription = "Clear", tint = Color(0xFF71717A))
                        }
                    }
                },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("home_search_bar"),
                shape = RoundedCornerShape(12.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = Color(0xFFFFFFFF),
                    unfocusedContainerColor = Color(0xFFF9FAFB),
                    focusedBorderColor = Color(0xFF6E56CF),
                    unfocusedBorderColor = Color(0xFFE4E4E7)
                )
            )

            Spacer(modifier = Modifier.height(20.dp))
            
            // Header for Recent Capsule
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Recent Capsule",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF71717A)
                )
                if (list.size > 1) {
                    Text(
                        text = "View all ${list.size}",
                        style = MaterialTheme.typography.labelMedium,
                        color = Color(0xFF6E56CF),
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .clickable { viewModel.navigateTo(Screen.Library) }
                            .padding(horizontal = 4.dp, vertical = 2.dp)
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(8.dp))
            
            // Filter capsule list dynamically
            val finalFilteredList = remember(list, searchQuery) {
                if (searchQuery.trim().isEmpty()) {
                    list
                } else {
                    list.filter {
                        it.name.contains(searchQuery, ignoreCase = true) ||
                        it.aiName.contains(searchQuery, ignoreCase = true) ||
                        it.content.contains(searchQuery, ignoreCase = true)
                    }
                }
            }

            if (finalFilteredList.isEmpty()) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("recent_capsule_empty_card"),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = Color.White),
                    border = BorderStroke(1.dp, Color(0xFFE4E4E7))
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            imageVector = Icons.Default.Inbox,
                            contentDescription = "Empty",
                            tint = Color(0xFFD1D5DB),
                            modifier = Modifier.size(36.dp)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = if (searchQuery.isNotEmpty()) "No matches found" else "No saved capsules yet",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            color = Color(0xFF71717A)
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = if (searchQuery.isNotEmpty()) "Try a different search term" else "Save a capsule thread from any AI partner to see it here.",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFF94A3B8)
                        )
                    }
                }
            } else {
                // Show ONLY ONE Recent Capsule (index 0)
                val recentCapsule = finalFilteredList[0]
                val relativeTime = getRelativeTime(recentCapsule.timestamp)
                
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { viewModel.selectedCapsuleForDetail.value = recentCapsule }
                        .testTag("recent_capsule_card"),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = Color.White),
                    border = BorderStroke(1.dp, Color(0xFFE4E4E7))
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .background(Color(0xFFF4F4F5), shape = RoundedCornerShape(10.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.FolderOpen,
                                contentDescription = "Capsule File",
                                tint = getAiColor(recentCapsule.aiName),
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        
                        Spacer(modifier = Modifier.width(12.dp))
                        
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = recentCapsule.name,
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF18181B),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "${recentCapsule.aiName} • $relativeTime",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFF71717A)
                            )
                        }
                        
                        Spacer(modifier = Modifier.width(8.dp))
                        
                        Icon(
                            imageVector = Icons.Default.ChevronRight,
                            contentDescription = "Open Capsule",
                            tint = Color(0xFF94A3B8),
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Statistics Row (Compact list)
            val capsCount = list.size
            val weekCount = list.count { it.timestamp >= (System.currentTimeMillis() - 7 * 24 * 60 * 60 * 1000L) }
            
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("statistics_card"),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White),
                border = BorderStroke(1.dp, Color(0xFFE4E4E7))
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 14.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Stat 1: Capsules
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.weight(1f)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.FolderOpen,
                                contentDescription = "Capsules Count",
                                tint = Color(0xFF6E56CF),
                                modifier = Modifier.size(16.dp)
                            )
                            Text(
                                text = capsCount.toString(),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.ExtraBold,
                                color = Color(0xFF18181B)
                            )
                        }
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "Capsules",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFF71717A)
                        )
                    }
                    
                    Box(
                        modifier = Modifier
                            .width(1.dp)
                            .height(24.dp)
                            .background(Color(0xFFE4E4E7))
                    )
                    
                    // Stat 2: AI Platforms
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.weight(1f)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Layers,
                                contentDescription = "Platforms Count",
                                tint = Color(0xFF10A37F),
                                modifier = Modifier.size(16.dp)
                            )
                            Text(
                                text = "3",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.ExtraBold,
                                color = Color(0xFF18181B)
                            )
                        }
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "AI Platforms",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFF71717A)
                        )
                    }
                    
                    Box(
                        modifier = Modifier
                            .width(1.dp)
                            .height(24.dp)
                            .background(Color(0xFFE4E4E7))
                    )
                    
                    // Stat 3: This Week
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.weight(1f)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.TrendingUp,
                                contentDescription = "Week Stats",
                                tint = Color(0xFFEC4899),
                                modifier = Modifier.size(16.dp)
                            )
                            Text(
                                text = weekCount.toString(),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.ExtraBold,
                                color = Color(0xFF18181B)
                            )
                        }
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "This Week",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFF71717A)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))
            
            // Quick Actions Container for AI platforms
            Text(
                text = "Quick Actions",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF71717A),
                modifier = Modifier.align(Alignment.Start)
            )
            
            Spacer(modifier = Modifier.height(8.dp))
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Quick Action 1: Claude
                QuickActionItem(
                    name = "Claude",
                    iconLogo = {
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .background(Color(0xFFFDF6F0), shape = CircleShape)
                                .border(1.dp, Color(0xFFE4E4E7), CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "C",
                                color = Color(0xFFCC5A37),
                                fontWeight = FontWeight.Black,
                                fontSize = 18.sp,
                                fontFamily = FontFamily.Serif
                            )
                        }
                    },
                    onClick = {
                        viewModel.navigateTo(Screen.WebViewScreen("Claude", "https://claude.ai"))
                    }
                )
                
                // Quick Action 2: ChatGPT
                QuickActionItem(
                    name = "ChatGPT",
                    iconLogo = {
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .background(Color(0xFFF0FDF4), shape = CircleShape)
                                .border(1.dp, Color(0xFFE4E4E7), CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            // Circular icon representing ChatGPT
                            Box(
                                modifier = Modifier
                                    .size(14.dp)
                                    .background(Color(0xFF10A37F), shape = CircleShape)
                            )
                        }
                    },
                    onClick = {
                        viewModel.navigateTo(Screen.WebViewScreen("ChatGPT", "https://chatgpt.com"))
                    }
                )
                
                // Quick Action 3: Gemini
                QuickActionItem(
                    name = "Gemini",
                    iconLogo = {
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .background(Color(0xFFEEF2FF), shape = CircleShape)
                                .border(1.dp, Color(0xFFE4E4E7), CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            // Spark shape representing Gemini
                            Canvas(modifier = Modifier.size(14.dp)) {
                                val path = Path().apply {
                                    val w = size.width
                                    val h = size.height
                                    moveTo(w / 2f, 0f)
                                    quadraticTo(w / 2f, h / 2f, w, h / 2f)
                                    quadraticTo(w / 2f, h / 2f, w / 2f, h)
                                    quadraticTo(w / 2f, h / 2f, 0f, h / 2f)
                                    quadraticTo(w / 2f, h / 2f, w / 2f, 0f)
                                    close()
                                }
                                drawPath(path, color = Color(0xFF4F46E5))
                            }
                        }
                    },
                    onClick = {
                        viewModel.navigateTo(Screen.WebViewScreen("Gemini", "https://gemini.google.com"))
                    }
                )
                
                // Quick Action 4: More
                QuickActionItem(
                    name = "More",
                    iconLogo = {
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .background(Color(0xFFFAFAFA), shape = CircleShape)
                                .border(1.dp, Color(0xFFE4E4E7), CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Add,
                                contentDescription = "More",
                                tint = Color(0xFF71717A),
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    },
                    onClick = {
                        Toast.makeText(context, "More AI models coming soon!", Toast.LENGTH_SHORT).show()
                    }
                )
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Primary Indigo Call to Action
            Button(
                onClick = { showNewCapsulePromptChoice = true },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF6E56CF)),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .testTag("home_new_capsule_button")
            ) {
                Icon(Icons.Default.Add, contentDescription = "Add", tint = Color.White, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("New Capsule", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp)
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Trust Platform Banner
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("trust_card"),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFFF4F4F5)),
                border = BorderStroke(1.dp, Color(0xFFE4E4E7))
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .background(Color.White, shape = CircleShape)
                            .border(1.dp, Color(0xFFE4E4E7), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Shield,
                            contentDescription = "Shield Check",
                            tint = Color(0xFF6E56CF),
                            modifier = Modifier.size(16.dp)
                        )
                    }
                    
                    Spacer(modifier = Modifier.width(12.dp))
                    
                    Column {
                        Text(
                            text = "Your capsules stay on your device.",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF18181B)
                        )
                        Spacer(modifier = Modifier.height(1.dp))
                        Text(
                            text = "No cloud storage. No accounts. You're in control.",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFF71717A)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Footer Text Block
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Canvas(modifier = Modifier.size(12.dp)) {
                    val path = Path().apply {
                        val w = size.width
                        val h = size.height
                        moveTo(w / 2f, 0f)
                        quadraticTo(w / 2f, h / 2f, w, h / 2f)
                        quadraticTo(w / 2f, h / 2f, w / 2f, h)
                        quadraticTo(w / 2f, h / 2f, 0f, h / 2f)
                        quadraticTo(w / 2f, h / 2f, w / 2f, 0f)
                        close()
                    }
                    drawPath(path, color = Color(0xFF7C3AED))
                }
                
                Spacer(modifier = Modifier.width(8.dp))
                
                Text(
                    text = "Built for people who use more than one AI and want an edge over average users.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF71717A),
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f)
                )
            }
        }

        // Choice Dialog triggered by + New Capsule
        if (showNewCapsulePromptChoice) {
            AlertDialog(
                onDismissRequest = { showNewCapsulePromptChoice = false },
                title = {
                    Text(
                        "Start New Prompt Session",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF18181B)
                    )
                },
                text = {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            "Choose an AI companion to launch. Simply start your conversation, then click 'Save Capsule' at the bottom to archive.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color(0xFF71717A)
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        
                        OutlinedButton(
                            onClick = {
                                showNewCapsulePromptChoice = false
                                viewModel.navigateTo(Screen.WebViewScreen("Claude", "https://claude.ai"))
                            },
                            modifier = Modifier.fillMaxWidth().height(44.dp),
                            shape = RoundedCornerShape(8.dp),
                            border = BorderStroke(1.dp, Color(0xFFE4E4E7))
                        ) {
                            Text("Launch Claude Sandbox", color = Color(0xFFCC5A37), fontWeight = FontWeight.Bold)
                        }
                        
                        OutlinedButton(
                            onClick = {
                                showNewCapsulePromptChoice = false
                                viewModel.navigateTo(Screen.WebViewScreen("ChatGPT", "https://chatgpt.com"))
                            },
                            modifier = Modifier.fillMaxWidth().height(44.dp),
                            shape = RoundedCornerShape(8.dp),
                            border = BorderStroke(1.dp, Color(0xFFE4E4E7))
                        ) {
                            Text("Launch ChatGPT Sandbox", color = Color(0xFF10A37F), fontWeight = FontWeight.Bold)
                        }
                        
                        OutlinedButton(
                            onClick = {
                                showNewCapsulePromptChoice = false
                                viewModel.navigateTo(Screen.WebViewScreen("Gemini", "https://gemini.google.com"))
                            },
                            modifier = Modifier.fillMaxWidth().height(44.dp),
                            shape = RoundedCornerShape(8.dp),
                            border = BorderStroke(1.dp, Color(0xFFE4E4E7))
                        ) {
                            Text("Launch Gemini Sandbox", color = Color(0xFF4F46E5), fontWeight = FontWeight.Bold)
                        }
                    }
                },
                confirmButton = {},
                dismissButton = {
                    TextButton(onClick = { showNewCapsulePromptChoice = false }) {
                        Text("Cancel", color = Color(0xFF71717A))
                    }
                }
            )
        }
    }

    @Composable
    fun QuickActionItem(
        name: String,
        iconLogo: @Composable () -> Unit,
        onClick: () -> Unit
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .clickable(onClick = onClick)
                .padding(8.dp)
                .testTag("quick_action_${name.lowercase()}")
        ) {
            iconLogo()
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = name,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium,
                color = Color(0xFF18181B)
            )
        }
    }

    @Composable
    fun ContextDropLogo(modifier: Modifier = Modifier) {
        Box(
            modifier = modifier
                .background(Color(0xFF09090B), shape = CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Canvas(modifier = Modifier.fillMaxSize(0.55f)) {
                val path = Path().apply {
                    val w = size.width
                    val h = size.height
                    moveTo(w / 2f, 0f)
                    quadraticTo(w / 2f, h / 2f, w, h / 2f)
                    quadraticTo(w / 2f, h / 2f, w / 2f, h)
                    quadraticTo(w / 2f, h / 2f, 0f, h / 2f)
                    quadraticTo(w / 2f, h / 2f, w / 2f, 0f)
                    close()
                }
                drawPath(path, color = Color(0xFF7C3AED))
            }
        }
    }

    @Composable
    fun WebViewContainer(aiName: String, url: String) {
        Column(modifier = Modifier.fillMaxSize()) {
            
            // Premium Light Web Header (cohesive with Notion White Design)
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = Color.White,
                border = BorderStroke(1.dp, Color(0xFFE4E4E7)),
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
                            tint = Color(0xFF18181B)
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
                            color = Color(0xFF18181B)
                        )
                        Text(
                            text = when (aiName) {
                                "ChatGPT" -> "chatgpt.com"
                                "Claude" -> "claude.ai"
                                "Gemini" -> "gemini.google.com"
                                else -> ""
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFF71717A)
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
                                .background(Color(0xFF10A37F))
                        )
                        Text(
                            text = "Sandbox",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF71717A),
                            letterSpacing = 0.5.sp
                        )
                    }
                }
            }

            // Central WebView box
            Box(
                modifier = Modifier
                    .weight(1f)
                    .background(Color.White)
            ) {
                key(aiName) {
                    AndroidView(
                        factory = {
                            val webView = getOrCreateWebView(aiName, url)
                            (webView.parent as? android.view.ViewGroup)?.removeView(webView)
                            webView.onResume()
                            webView
                        },
                        modifier = Modifier
                            .fillMaxSize()
                            .testTag("webview_$aiName"),
                        onRelease = { webView ->
                            if (!isActivityDestroyed) {
                                try {
                                    webView.clearFocus()
                                    webView.onPause()
                                    (webView.parent as? android.view.ViewGroup)?.removeView(webView)
                                } catch (e: Exception) {
                                    e.printStackTrace()
                                }
                            }
                        }
                    )
                }
            }

            // Material 3 premium browser operations panel
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("webview_bottom_navigation_bar"),
                color = Color.White,
                border = BorderStroke(1.dp, Color(0xFFE4E4E7)),
                shadowElevation = 4.dp
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    
                    // Button 1: Save Capsule
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
                            containerColor = Color(0xFF6E56CF),
                            contentColor = Color.White
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

                    // Button 2: Library
                    OutlinedButton(
                        onClick = { viewModel.navigateTo(Screen.Library) },
                        modifier = Modifier
                            .weight(0.9f)
                            .height(48.dp)
                            .testTag("library_navigation_button"),
                        shape = RoundedCornerShape(100.dp),
                        border = BorderStroke(1.dp, Color(0xFFE4E4E7)),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = Color(0xFF71717A)
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
                .background(Color.White)
        ) {
            TopAppBar(
                title = { Text("Capsule Library", fontWeight = FontWeight.Bold, color = Color(0xFF18181B)) },
                navigationIcon = {
                    IconButton(
                        onClick = { viewModel.navigateTo(Screen.Home) },
                        modifier = Modifier.testTag("library_back_button")
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Go back", tint = Color(0xFF18181B))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.White)
            )

            // Search Filter Row
            OutlinedTextField(
                value = searchInput,
                onValueChange = { searchInput = it },
                placeholder = { Text("Search stored capsules...", color = Color(0xFF94A3B8)) },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = "Search", tint = Color(0xFF94A3B8)) },
                trailingIcon = {
                    if (searchInput.isNotEmpty()) {
                        IconButton(onClick = { searchInput = "" }) {
                            Icon(Icons.Filled.Close, contentDescription = "Clear", tint = Color(0xFF71717A))
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
                    focusedContainerColor = Color(0xFFFFFFFF),
                    unfocusedContainerColor = Color(0xFFF9FAFB),
                    focusedBorderColor = Color(0xFF6E56CF),
                    unfocusedBorderColor = Color(0xFFE4E4E7)
                )
            )

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
                        contentDescription = "Empty",
                        modifier = Modifier.size(56.dp),
                        tint = Color(0xFFD1D5DB)
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = if (searchInput.isNotEmpty()) "No matches found" else "Your Capsule Library is empty",
                        style = MaterialTheme.typography.titleMedium,
                        color = Color(0xFF71717A),
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = if (searchInput.isNotEmpty()) "Try searching different words." else "Open a Web Sandbox screen to save real capsule threads.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFF94A3B8),
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
            colors = CardDefaults.cardColors(containerColor = Color.White),
            border = BorderStroke(1.dp, Color(0xFFE4E4E7))
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = capsule.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF18181B),
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = getAiColor(capsule.aiName).copy(alpha = 0.12f)
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

                Text(
                    text = dateString,
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFF71717A)
                )

                Spacer(modifier = Modifier.height(10.dp))

                Text(
                    text = capsule.content,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF52525B),
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
            "Claude" -> Color(0xFFCC5A37)
            "Gemini" -> Color(0xFF4F46E5)
            else -> Color(0xFF71717A)
        }
    }

    private fun getRelativeTime(timestamp: Long): String {
        val diff = System.currentTimeMillis() - timestamp
        return when {
            diff < 60000 -> "Just now"
            diff < 3600000 -> "${diff / 60000}m ago"
            diff < 86400000 -> "${diff / 3600000}h ago"
            diff < 172800000 -> "Yesterday"
            else -> SimpleDateFormat("MMM d, yyyy", Locale.getDefault()).format(Date(timestamp))
        }
    }

    private fun saveCapsuleToDownloads(context: android.content.Context, filename: String, content: String) {
        try {
            val resolvedFilename = if (filename.endsWith(".txt")) filename else "$filename.txt"
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val resolver = context.contentResolver
                val contentValues = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, resolvedFilename)
                    put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                }
                
                val uri: Uri? = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
                if (uri != null) {
                    resolver.openOutputStream(uri).use { outputStream ->
                        outputStream?.write(content.toByteArray())
                    }
                    Toast.makeText(context, "Saved to Downloads", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(context, "Failed to create file", Toast.LENGTH_SHORT).show()
                }
            } else {
                val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                if (!downloadsDir.exists()) {
                    downloadsDir.mkdirs()
                }
                val file = java.io.File(downloadsDir, resolvedFilename)
                java.io.FileOutputStream(file).use { outputStream ->
                    outputStream.write(content.toByteArray())
                }
                Toast.makeText(context, "Saved to Downloads", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(context, "Error: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
        }
    }

    // Helper to draw clean cubic paths for custom Gemini sparks
    private fun Path.cubicAsBezier(x1: Float, y1: Float, ctrlX: Float, ctrlY: Float, x2: Float, y2: Float) {
        cubicTo(x1, y1, ctrlX, ctrlY, x2, y2)
    }
}

