package apps.visnkmr.batu

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.BorderStroke
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import apps.visnkmr.batu.ui.theme.TVCalendarTheme
import apps.visnkmr.batu.data.AppDatabase
import apps.visnkmr.batu.data.ChatRepository
import apps.visnkmr.batu.data.Conversation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okio.BufferedSource
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

// Navigation routes for TV screens
object TVRoutes {
    const val HOME = "tv_home"
    const val CHAT = "tv_chat/{conversationId}"
    const val MODEL_SEARCH = "tv_model_search"

    fun createChatRoute(conversationId: Long) = "tv_chat/$conversationId"
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun focusableLayout(
    modifier: Modifier = Modifier,
    isClickable: Boolean = false,
    onClick: () -> Unit = {},
    content: @Composable () -> Unit
) {
    val focusManager = LocalFocusManager.current
    val requester = remember { FocusRequester() }
    val focusedState = remember { mutableStateOf(false) }
    val focused = focusedState.value
    val interaction = remember { MutableInteractionSource() }

    // Animated values
    val scale by animateFloatAsState(
        targetValue = if (focused) 1.05f else 1.0f,
        animationSpec = tween(durationMillis = 200),
        label = "scale"
    )

    val tonalElevation by animateFloatAsState(
        targetValue = if (focused) 6f else 2f,
        animationSpec = tween(durationMillis = 200),
        label = "tonalElevation"
    )

    val shadowElevation by animateFloatAsState(
        targetValue = if (focused) 8f else 2f,
        animationSpec = tween(durationMillis = 200),
        label = "shadowElevation"
    )

    androidx.compose.material3.Surface(
        tonalElevation = tonalElevation.dp,
        shadowElevation = shadowElevation.dp,
        shape = RoundedCornerShape(12.dp),
        color = if (focused) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surface,
        modifier = if (isClickable) {
            modifier
                .onFocusChanged { focusedState.value = it.isFocused }
                .focusable(true, interactionSource = interaction)
                .scale(scale)
                .combinedClickable(
                    interactionSource = interaction,
                    indication = null,
                    onClick = onClick,
                    onLongClick = onClick
                )
        } else {
            modifier
                .onFocusChanged { focusedState.value = it.isFocused }
                .focusable(true, interactionSource = interaction)
                .scale(scale)
        }
    ) {
        content()
    }
}

@Composable
fun TVChatScreen(
    context: Context,
    prefs: android.content.SharedPreferences,
    dark: Boolean,
    onToggleDark: () -> Unit,
    repo: ChatRepository
) {
    val scope = rememberCoroutineScope()
    val navController = rememberNavController()
    var conversations by remember { mutableStateOf<List<Conversation>>(emptyList()) }
    var apiKey by remember { mutableStateOf(prefs.getString("openrouter_api_key", "") ?: "") }

    // Model selection
    var allModels by remember { mutableStateOf(listOf<String>()) }
    var freeModels by remember { mutableStateOf(setOf<String>()) }
    var loadingModels by remember { mutableStateOf(false) }
    var selectedModel by remember { mutableStateOf("openrouter/auto") }

    // OkHttp client
    val client = remember {
        OkHttpClient.Builder()
            .retryOnConnectionFailure(true)
            .build()
    }

    // Load conversations
    LaunchedEffect(Unit) {
        repo.conversations().collect { convs ->
            conversations = convs
        }
    }

    TVCalendarTheme(darkTheme = dark) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            // Handle back button navigation
            androidx.compose.ui.platform.LocalView.current.setOnKeyListener { _, keyCode, event ->
                if (event.action == android.view.KeyEvent.ACTION_DOWN) {
                    when (keyCode) {
                        android.view.KeyEvent.KEYCODE_BACK -> {
                            navController.navigateUp()
                            true
                        }
                        else -> false
                    }
                } else {
                    false
                }
            }

            NavHost(navController = navController, startDestination = TVRoutes.HOME) {
                composable(TVRoutes.HOME) {
                    val onLoadModelsAction = {
                        if (allModels.isEmpty() && !loadingModels) {
                            loadingModels = true
                            scope.launch {
                                try {
                                    val reqBuilder = Request.Builder()
                                        .url("https://openrouter.ai/api/v1/models")
                                        .get()
                                    if (apiKey.isNotBlank()) {
                                        reqBuilder.addHeader("Authorization", "Bearer $apiKey")
                                        reqBuilder.addHeader("HTTP-Referer", "https://example.com")
                                        reqBuilder.addHeader("X-Title", "Batu Chat")
                                    }
                                    val req = reqBuilder.build()
                                    val resp: Response = withContext(Dispatchers.IO) { client.newCall(req).execute() }
                                    resp.use { r ->
                                        if (!r.isSuccessful) throw IOException("HTTP ${r.code} ${r.message}")
                                        val body = r.body?.string().orEmpty()
                                        val root = JSONObject(body)
                                        val data = root.optJSONArray("data") ?: JSONArray()
                                        val names = mutableListOf<String>()
                                        val freeSet = mutableSetOf<String>()
                                        for (i in 0 until data.length()) {
                                            val item = data.optJSONObject(i) ?: continue
                                            val idRaw = item.optString("id")
                                            if (idRaw.isBlank()) continue
                                            val id = idRaw
                                            names.add(id)
                                            val pricing = item.optJSONObject("pricing")
                                            val prompt = pricing?.opt("prompt")
                                            val completion = pricing?.opt("completion")
                                            val inputFree = prompt == null || prompt == JSONObject.NULL || (prompt is String && prompt.equals("0", true))
                                            val outputFree = completion == null || completion == JSONObject.NULL || (completion is String && completion.equals("0", true))
                                            val likelyFree = inputFree && outputFree
                                            if (likelyFree) {
                                                freeSet.add(id)
                                            }
                                        }
                                        allModels = names
                                        freeModels = freeSet
                                    }
                                } catch (e: Exception) {
                                    // Handle error silently for TV
                                } finally {
                                    loadingModels = false
                                }
                            }
                        }
                    }

                    TVHomeScreen(
                        conversations = conversations,
                        selectedModel = selectedModel,
                        allModels = allModels,
                        freeModels = freeModels,
                        loadingModels = loadingModels,
                        apiKey = apiKey,
                        onModelSelected = { selectedModel = it },
                        onConversationSelected = { conversationId ->
                            navController.navigate(TVRoutes.createChatRoute(conversationId))
                        },
                        onNewConversation = {
                            scope.launch {
                                val newId = repo.newConversation()
                                navController.navigate(TVRoutes.createChatRoute(newId))
                            }
                        },
                        onLoadModels = onLoadModelsAction,
                        onNavigateToModelSearch = {
                            navController.navigate(TVRoutes.MODEL_SEARCH)
                        }
                    )
                }

                composable(
                    route = TVRoutes.CHAT,
                    arguments = listOf(navArgument("conversationId") { type = NavType.LongType })
                ) { backStackEntry ->
                    val conversationId = backStackEntry.arguments?.getLong("conversationId") ?: return@composable
                    TVChatDetailScreen(
                        conversationId = conversationId,
                        selectedModel = selectedModel,
                        apiKey = apiKey,
                        repo = repo,
                        onBack = { navController.navigateUp() }
                    )
                }

                composable(TVRoutes.MODEL_SEARCH) {
                    TVModelSearchScreen(
                        allModels = allModels,
                        freeModels = freeModels,
                        selectedModel = selectedModel,
                        onModelSelected = { selectedModel = it },
                        onBack = { navController.navigateUp() },
                        onLoadMoreModels = {
                            // Load more models logic would go here
                        }
                    )
                }
            }
        }
    }
}

@Composable
fun TVHomeScreen(
    conversations: List<Conversation>,
    selectedModel: String,
    allModels: List<String>,
    freeModels: Set<String>,
    loadingModels: Boolean,
    apiKey: String,
    onModelSelected: (String) -> Unit,
    onConversationSelected: (Long) -> Unit,
    onNewConversation: () -> Unit,
    onLoadModels: () -> Unit,
    onNavigateToModelSearch: () -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        // Row 1: Model Selection (Netflix-style cards)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .height(160.dp)
                .padding(16.dp)
        ) {
            Text("Models:", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(16.dp))
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                items(allModels.take(6)) { model ->
                    val isFree = freeModels.contains(model)
                    focusableLayout(
                        modifier = Modifier
                            .width(200.dp)
                            .height(120.dp),
                        isClickable = true,
                        onClick = { onModelSelected(model) }
                    ) {
                        Card(
                            modifier = Modifier.fillMaxSize(),
                            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = if (model == selectedModel) MaterialTheme.colorScheme.primaryContainer
                                                else MaterialTheme.colorScheme.surface
                            )
                        ) {
                            Box(modifier = Modifier.fillMaxSize()) {
                                // FREE badge in top-right corner
                                if (isFree) {
                                    Box(
                                        modifier = Modifier
                                            .align(Alignment.TopEnd)
                                            .background(
                                                color = Color(0xFF4CAF50),
                                                shape = RoundedCornerShape(bottomStart = 8.dp)
                                            )
                                            .padding(horizontal = 8.dp, vertical = 4.dp)
                                    ) {
                                        Text(
                                            text = "FREE",
                                            color = Color.White,
                                            fontSize = 10.sp,
                                            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                                        )
                                    }
                                }

                                // Main content
                                Column(
                                    modifier = Modifier.align(Alignment.Center),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Text("🤖", fontSize = 32.sp)
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text(
                                        text = model,
                                        fontSize = 12.sp,
                                        maxLines = 2,
                                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                                    )
                                }
                            }
                        }
                    }
                }

                // Search Models card
                item {
                    focusableLayout(
                        modifier = Modifier
                            .width(200.dp)
                            .height(120.dp),
                        isClickable = true,
                        onClick = {
                            // Navigate to model search screen
                            onNavigateToModelSearch()
                        }
                    ) {
                        Card(
                            modifier = Modifier.fillMaxSize(),
                            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
                        ) {
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text("🔍", fontSize = 32.sp)
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text("Search", fontSize = 14.sp)
                                }
                            }
                        }
                    }
                }

                // More Models card
                if (allModels.size > 6) {
                    item {
                        focusableLayout(
                            modifier = Modifier
                                .width(200.dp)
                                .height(120.dp),
                            isClickable = true,
                            onClick = {
                                // Show all models in selection dialog
                                onLoadModels()
                            }
                        ) {
                            Card(
                                modifier = Modifier.fillMaxSize(),
                                elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
                            ) {
                                Box(
                                    modifier = Modifier.fillMaxSize(),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Text("➕", fontSize = 32.sp)
                                        Spacer(modifier = Modifier.height(8.dp))
                                        Text("More", fontSize = 14.sp)
                                    }
                                }
                            }
                        }
                    }
                }
                if (allModels.isEmpty() && !loadingModels) {
                    item {
                        focusableLayout(
                            modifier = Modifier
                                .width(200.dp)
                                .height(120.dp),
                            isClickable = true,
                            onClick = onLoadModels
                        ) {
                            Card(
                                modifier = Modifier.fillMaxSize(),
                                elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
                            ) {
                                Box(
                                    modifier = Modifier.fillMaxSize(),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Text("🔄", fontSize = 24.sp)
                                        Spacer(modifier = Modifier.height(8.dp))
                                        Text("Load Models", fontSize = 12.sp)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // Row 2: Questions across chats (Netflix-style cards)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .height(160.dp)
                .padding(16.dp)
        ) {
            Text("Questions:", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(16.dp))
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Show recent questions from all conversations
                val recentQuestions = conversations.flatMap { conv ->
                    // This would need to be implemented to get questions from each conversation
                    // For now, just show conversation titles
                    listOf("Q from ${conv.title}")
                }.take(8)

                items(recentQuestions) { question ->
                    focusableLayout(
                        modifier = Modifier
                            .width(200.dp)
                            .height(120.dp),
                        isClickable = true,
                        onClick = {
                            // Navigate to the conversation containing this question
                            conversations.firstOrNull()?.let { conv ->
                                onConversationSelected(conv.id)
                            }
                        }
                    ) {
                        Card(
                            modifier = Modifier.fillMaxSize(),
                            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
                        ) {
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text("💭", fontSize = 32.sp)
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text(
                                        text = question,
                                        fontSize = 12.sp,
                                        maxLines = 2,
                                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // Row 3: Previous chats with New Chat as first button (Netflix-style horizontal layout)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(16.dp)
        ) {
            Text("Chats:", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(16.dp))

            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // New Chat as first item
                item {
                    focusableLayout(
                        modifier = Modifier
                            .width(200.dp)
                            .height(120.dp),
                        isClickable = true,
                        onClick = onNewConversation
                    ) {
                        Card(
                            modifier = Modifier.fillMaxSize(),
                            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
                        ) {
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text("✨", fontSize = 32.sp)
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text("New Chat", fontSize = 14.sp)
                                }
                            }
                        }
                    }
                }

                // Previous conversations as large cards
                items(conversations) { conv ->
                    focusableLayout(
                        modifier = Modifier
                            .width(200.dp)
                            .height(120.dp),
                        isClickable = true,
                        onClick = { onConversationSelected(conv.id) }
                    ) {
                        Card(
                            modifier = Modifier.fillMaxSize(),
                            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
                        ) {
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text("💬", fontSize = 32.sp)
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text(
                                        text = conv.title.ifBlank { "Chat ${conversations.indexOf(conv) + 1}" },
                                        fontSize = 12.sp,
                                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                        maxLines = 2
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

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun TVChatDetailScreen(
    conversationId: Long,
    selectedModel: String,
    apiKey: String,
    repo: ChatRepository,
    onBack: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var conversation by remember { mutableStateOf<Conversation?>(null) }
    var messages by remember { mutableStateOf<List<String>>(emptyList()) }
    var inputText by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }

    // Load conversation
    LaunchedEffect(conversationId) {
        try {
            conversation = repo.getConversation(conversationId)
            // Load messages - placeholder for now
            messages = listOf()
        } catch (e: Exception) {
            // Handle error silently for TV
            conversation = null
        }
    }

    TVCalendarTheme(darkTheme = true) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Header with back button and title (search-style layout)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    focusableLayout(
                        modifier = Modifier
                            .width(80.dp)
                            .height(48.dp),
                        isClickable = true,
                        onClick = onBack
                    ) {
                        OutlinedButton(
                            onClick = {},
                            modifier = Modifier.fillMaxSize()
                        ) {
                            Text("← Back")
                        }
                    }
                    Spacer(modifier = Modifier.width(16.dp))
                    Text("Chat", style = MaterialTheme.typography.titleLarge)
                }

                // Search-style input field (large, prominent like the model search)
                focusableLayout(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(80.dp)
                        .padding(horizontal = 16.dp),
                    isClickable = true,
                    onClick = {
                        // TODO: Show keyboard for input
                    }
                ) {
                    OutlinedTextField(
                        value = inputText,
                        onValueChange = { inputText = it },
                        modifier = Modifier.fillMaxSize(),
                        placeholder = {
                            Text(
                                "Type your message...",
                                style = MaterialTheme.typography.headlineMedium,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center
                            )
                        },
                        textStyle = MaterialTheme.typography.headlineMedium.copy(
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        ),
                        singleLine = true
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Chat results area (using search-style layout with cards)
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 16.dp)
                ) {
                    items(messages) { message ->
                        val isUserMessage = messages.indexOf(message) % 2 == 0
                        focusableLayout(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(60.dp),
                            isClickable = false
                        ) {
                            Card(
                                modifier = Modifier.fillMaxSize(),
                                elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
                                colors = CardDefaults.cardColors(
                                    containerColor = if (isUserMessage)
                                        MaterialTheme.colorScheme.primaryContainer
                                    else MaterialTheme.colorScheme.surface
                                )
                            ) {
                                Box(modifier = Modifier.fillMaxSize()) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .padding(16.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = if (isUserMessage) "👤" else "🤖",
                                            fontSize = 20.sp
                                        )
                                        Spacer(modifier = Modifier.width(12.dp))
                                        Text(
                                            text = message,
                                            style = MaterialTheme.typography.bodyLarge,
                                            modifier = Modifier.weight(1f)
                                        )
                                    }
                                }
                            }
                        }
                    }

                    if (isLoading) {
                        item {
                            focusableLayout(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(60.dp),
                                isClickable = false
                            ) {
                                Card(
                                    modifier = Modifier.fillMaxSize(),
                                    elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
                                    colors = CardDefaults.cardColors(
                                        containerColor = MaterialTheme.colorScheme.primaryContainer
                                    )
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .padding(16.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(20.dp),
                                            strokeWidth = 2.dp
                                        )
                                        Spacer(modifier = Modifier.width(12.dp))
                                        Text(
                                            text = "AI is thinking...",
                                            style = MaterialTheme.typography.bodyLarge
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // Send button as the last item (like Load More button in search)
                    item {
                        focusableLayout(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(60.dp),
                            isClickable = true,
                            onClick = {
                                if (inputText.isNotBlank() && !isLoading) {
                                    val userMessage = inputText
                                    inputText = ""
                                    messages = messages + userMessage
                                    isLoading = true

                                    // TODO: Send to API and get response
                                    scope.launch {
                                        kotlinx.coroutines.delay(2000) // Simulate API call
                                        messages = messages + "AI Response to: $userMessage"
                                        isLoading = false
                                    }
                                }
                            }
                        ) {
                            OutlinedButton(
                                onClick = {},
                                modifier = Modifier.fillMaxSize(),
                                enabled = inputText.isNotBlank() && !isLoading
                            ) {
                                Text("Send Message", style = MaterialTheme.typography.titleMedium)
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun TVModelSearchScreen(
    allModels: List<String>,
    freeModels: Set<String>,
    selectedModel: String,
    onModelSelected: (String) -> Unit,
    onBack: () -> Unit,
    onLoadMoreModels: () -> Unit
) {
    val searchQuery = remember { mutableStateOf("") }
    val filteredModels = remember(searchQuery.value, allModels) {
        if (searchQuery.value.isBlank()) {
            allModels
        } else {
            allModels.filter { it.contains(searchQuery.value, ignoreCase = true) }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Header with back button and title
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                focusableLayout(
                    modifier = Modifier
                        .width(80.dp)
                        .height(48.dp),
                    isClickable = true,
                    onClick = onBack
                ) {
                    OutlinedButton(
                        onClick = {},
                        modifier = Modifier.fillMaxSize()
                    ) {
                        Text("← Back")
                    }
                }
                Spacer(modifier = Modifier.width(16.dp))
                Text("Search Models", style = MaterialTheme.typography.titleLarge)
            }

            // Search input field
            focusableLayout(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(80.dp)
                    .padding(horizontal = 16.dp),
                isClickable = false
            ) {
                OutlinedTextField(
                    value = searchQuery.value,
                    onValueChange = { searchQuery.value = it },
                    modifier = Modifier.fillMaxSize(),
                    placeholder = {
                        Text(
                            "Type to search models...",
                            style = MaterialTheme.typography.headlineMedium,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                    },
                    textStyle = MaterialTheme.typography.headlineMedium.copy(
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    ),
                    singleLine = true
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Model results
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(horizontal = 16.dp)
            ) {
                items(filteredModels) { model ->
                    val isFree = freeModels.contains(model)
                    focusableLayout(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(60.dp),
                        isClickable = true,
                        onClick = {
                            onModelSelected(model)
                            onBack()
                        }
                    ) {
                        Card(
                            modifier = Modifier.fillMaxSize(),
                            elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = if (model == selectedModel) MaterialTheme.colorScheme.primaryContainer
                                                else MaterialTheme.colorScheme.surface
                            )
                        ) {
                            Box(modifier = Modifier.fillMaxSize()) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(16.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text("🤖", fontSize = 20.sp)
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Text(
                                        text = model,
                                        style = MaterialTheme.typography.bodyLarge,
                                        modifier = Modifier.weight(1f)
                                    )
                                    if (isFree) {
                                        Text(
                                            text = "FREE",
                                            color = Color(0xFF4CAF50),
                                            fontSize = 12.sp,
                                            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                                        )
                                    }
                                }

                                // Selection indicator
                                if (model == selectedModel) {
                                    Box(
                                        modifier = Modifier
                                            .align(Alignment.TopEnd)
                                            .background(
                                                color = MaterialTheme.colorScheme.primary,
                                                shape = RoundedCornerShape(bottomStart = 8.dp)
                                            )
                                            .padding(horizontal = 8.dp, vertical = 4.dp)
                                    ) {
                                        Text(
                                            text = "SELECTED",
                                            color = Color.White,
                                            fontSize = 10.sp,
                                            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // Load more button
                if (filteredModels.size >= 5) {
                    item {
                        focusableLayout(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(60.dp),
                            isClickable = true,
                            onClick = onLoadMoreModels
                        ) {
                            OutlinedButton(
                                onClick = {},
                                modifier = Modifier.fillMaxSize()
                            ) {
                                Text("Load More Models", style = MaterialTheme.typography.titleMedium)
                            }
                        }
                    }
                }
            }
        }
    }
}