package apps.visnkmr.batu

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
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

@Composable
fun TVChatScreen(
    context: Context,
    prefs: android.content.SharedPreferences,
    dark: Boolean,
    onToggleDark: () -> Unit,
    repo: ChatRepository
) {
    val scope = rememberCoroutineScope()
    var selectedConversationId by remember { mutableStateOf<Long?>(null) }
    var conversations by remember { mutableStateOf<List<Conversation>>(emptyList()) }
    var input by remember { mutableStateOf("") }
    var apiKey by remember { mutableStateOf(prefs.getString("openrouter_api_key", "") ?: "") }

    // Model selection
    var showModels by remember { mutableStateOf(false) }
    var allModels by remember { mutableStateOf(listOf<String>()) }
    var freeModels by remember { mutableStateOf(setOf<String>()) }
    var visibleCount by remember { mutableStateOf(5) }
    var loadingModels by remember { mutableStateOf(false) }
    var modelsError by remember { mutableStateOf<String?>(null) }
    var filterFreeOnly by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var selectedModel by remember { mutableStateOf("openrouter/auto") }

    // Questions popup
    var showQuestions by remember { mutableStateOf(false) }

    // Actions menu
    var showActions by remember { mutableStateOf(false) }

    // List state for scrolling
    val listState = rememberLazyListState()

    // OkHttp client
    val client = remember {
        OkHttpClient.Builder()
            .retryOnConnectionFailure(true)
            .build()
    }

    // Load models function
    fun parseFreeFlagFromName(name: String): Boolean {
        val lower = name.lowercase()
        return listOf("openrouter/auto", "free", "gemma", "mistral", "llama", "qwen", "mixtral", "openhermes", "phi-3", "smollm").any { lower.contains(it) }
    }

    fun ensureModelsLoaded() {
        if (allModels.isNotEmpty() || loadingModels) return
        loadingModels = true
        modelsError = null
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
                        if (likelyFree || parseFreeFlagFromName(id)) {
                            freeSet.add(id)
                        }
                    }
                    allModels = names
                    freeModels = freeSet
                    visibleCount = 5
                }
            } catch (e: Exception) {
                modelsError = e.message ?: "Failed to load models"
            } finally {
                loadingModels = false
            }
        }
    }

    // Load conversations
    LaunchedEffect(Unit) {
        repo.conversations().collect { convs ->
            conversations = convs
            if (selectedConversationId == null && conversations.isNotEmpty()) {
                selectedConversationId = conversations.first().id
            }
        }
    }

    // Messages for selected conversation
    val messagesFlow = remember(selectedConversationId) {
        if (selectedConversationId != null) repo.messages(selectedConversationId!!) else null
    }
    val messages by (messagesFlow?.collectAsState(initial = emptyList()) ?: remember { mutableStateOf(emptyList()) })

    TVCalendarTheme(darkTheme = dark) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            // Header
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                focusableLayout(
                    modifier = Modifier.width(48.dp).height(48.dp),
                    isClickable = true,
                    onClick = { showActions = !showActions }
                ) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("🤖", fontSize = 20.sp)
                    }
                }
                Spacer(Modifier.width(16.dp))
                focusableLayout(
                    modifier = Modifier.weight(1f),
                    isClickable = true,
                    onClick = {
                        ensureModelsLoaded()
                        showModels = true
                    }
                ) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.CenterStart) {
                        Text(selectedModel)
                    }
                }
            }

            // Actions Menu
            if (showActions) {
                AlertDialog(
                    onDismissRequest = { showActions = false },
                    title = { Text("Actions") },
                    text = {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            focusableLayout(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(48.dp),
                                isClickable = true,
                                onClick = {
                                    showActions = false
                                    ensureModelsLoaded()
                                    showModels = true
                                }
                            ) {
                                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.CenterStart) {
                                    Text("Models")
                                }
                            }
                            Spacer(Modifier.height(8.dp))
                            focusableLayout(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(48.dp),
                                isClickable = true,
                                onClick = {
                                    showActions = false
                                    showQuestions = true
                                }
                            ) {
                                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.CenterStart) {
                                    Text("Questions")
                                }
                            }
                            Spacer(Modifier.height(8.dp))
                            focusableLayout(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(48.dp),
                                isClickable = true,
                                onClick = {
                                    showActions = false
                                    scope.launch {
                                        val newId = repo.newConversation()
                                        selectedConversationId = newId
                                    }
                                }
                            ) {
                                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.CenterStart) {
                                    Text("New Chat")
                                }
                            }
                        }
                    },
                    confirmButton = {
                        focusableLayout(
                            modifier = Modifier.width(80.dp).height(36.dp),
                            isClickable = true,
                            onClick = { showActions = false }
                        ) {
                            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Text("✕")
                            }
                        }
                    }
                )
            }

            Row(modifier = Modifier.fillMaxSize()) {
                // Left sidebar for conversations
                TVConversationList(
                    conversations = conversations,
                    selectedConversationId = selectedConversationId,
                    onConversationSelected = { selectedConversationId = it },
                    onNewConversation = {
                        scope.launch {
                            val newId = repo.newConversation()
                            selectedConversationId = newId
                        }
                    },
                    onShowModels = {
                        ensureModelsLoaded()
                        showModels = true
                    },
                    onShowQuestions = {
                        showQuestions = true
                    },
                    onSendMessage = {
                        val trimmed = input.trim()
                        val conv = selectedConversationId
                        if (trimmed.isNotEmpty() && conv != null && apiKey.isNotBlank()) {
                            scope.launch {
                                repo.addUserMessage(conv, trimmed)
                                input = ""
                                // TODO: Implement streaming chat
                            }
                        }
                    },
                    input = input
                )

                // Main chat area
                TVChatArea(
                    messages = messages,
                    input = input,
                    onInputChange = { input = it },
                    modifier = Modifier.weight(1f),
                    listState = listState
                )
            }
        }
    }

    // Model Selection Dialog
    if (showModels) {
        AlertDialog(
            onDismissRequest = { showModels = false },
            title = { Text("Select Model") },
            text = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    if (loadingModels) {
                        Text("Loading models…")
                        Spacer(Modifier.height(8.dp))
                    }
                    modelsError?.let { err ->
                        Text("Error: $err", color = Color(0xFFB00020))
                        Spacer(Modifier.height(8.dp))
                    }
                    focusableLayout(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp),
                        isClickable = false
                    ) {
                        OutlinedTextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            singleLine = true,
                            placeholder = { Text("Search models") },
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = filterFreeOnly, onCheckedChange = { filterFreeOnly = it })
                        Text("Free models only")
                    }
                    Spacer(Modifier.height(8.dp))
                    Divider()
                    Spacer(Modifier.height(8.dp))
                    val filtered = allModels.filter { m ->
                        val okFree = if (filterFreeOnly) freeModels.contains(m) else true
                        val okSearch = if (searchQuery.isNotBlank()) m.contains(searchQuery, ignoreCase = true) else true
                        okFree && okSearch
                    }
                    val toShow = filtered.take(visibleCount)
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(220.dp)
                    ) {
                        items(toShow) { m ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 6.dp)
                            ) {
                                val isFree = freeModels.contains(m)
                                focusableLayout(
                                    modifier = Modifier.width(40.dp).height(40.dp),
                                    isClickable = true,
                                    onClick = {
                                        selectedModel = m
                                        showModels = false
                                    }
                                ) {
                                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                        Text("✓")
                                    }
                                }
                                Spacer(Modifier.width(8.dp))
                                Text(m, modifier = Modifier.weight(1f))
                                if (isFree) {
                                    Spacer(Modifier.widthIn(6.dp))
                                    Text("FREE", color = Color(0xFF2E7D32))
                                }
                            }
                        }
                        item {
                            if (toShow.isEmpty()) {
                                Text("No models found")
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        focusableLayout(
                            modifier = Modifier.width(80.dp).height(36.dp),
                            isClickable = true,
                            onClick = {
                                val filteredSize = filtered.size
                                visibleCount = (visibleCount + 5).coerceAtMost(filteredSize)
                            }
                        ) {
                            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Text("+5")
                            }
                        }
                    }
                }
            },
            confirmButton = {
                focusableLayout(
                    modifier = Modifier.width(80.dp).height(36.dp),
                    isClickable = true,
                    onClick = { showModels = false }
                ) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("✕")
                    }
                }
            }
        )
    }

    // Questions Popup
    if (showQuestions) {
        val questionItems = remember(messages) {
            messages.mapIndexedNotNull { idx, m ->
                if (m.role == "user") Pair(idx, m) else null
            }
        }
        AlertDialog(
            onDismissRequest = { showQuestions = false },
            title = { Text("Questions in this thread") },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(360.dp)
                ) {
                    Divider()
                    Spacer(Modifier.height(8.dp))
                    LazyColumn(
                        modifier = Modifier.fillMaxHeight()
                    ) {
                        items(questionItems.size) { i ->
                            val (idx, msg) = questionItems[i]
                            focusableLayout(
                                modifier = Modifier.fillMaxWidth().height(40.dp),
                                isClickable = true,
                                onClick = {
                                    scope.launch {
                                        // Jump instantly without animation to avoid any UI effect
                                        listState.scrollToItem(idx)
                                        showQuestions = false
                                    }
                                }
                            ) {
                                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.CenterStart) {
                                    Text("▶")
                                    Spacer(Modifier.width(8.dp))
                                    Text(msg.content.take(120))
                                }
                            }
                            Divider()
                        }
                    }
                }
            },
            confirmButton = {
                focusableLayout(
                    modifier = Modifier.width(80.dp).height(36.dp),
                    isClickable = true,
                    onClick = { showQuestions = false }
                ) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("✕")
                    }
                }
            }
        )
    }
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

    androidx.compose.material3.Surface(
        tonalElevation = if (focused) 6.dp else 2.dp,
        shadowElevation = if (focused) 8.dp else 2.dp,
        shape = RoundedCornerShape(12.dp),
        color = if (focused) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surface,
        modifier = if (isClickable) {
            modifier
                .onFocusChanged { focusedState.value = it.isFocused }
                .focusable(true, interactionSource = interaction)
                .scale(if (focused) 1.05f else 1.0f)
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
                .scale(if (focused) 1.05f else 1.0f)
        }
    ) {
        content()
    }
}

@Composable
fun TVConversationList(
    conversations: List<Conversation>,
    selectedConversationId: Long?,
    onConversationSelected: (Long) -> Unit,
    onNewConversation: () -> Unit,
    onShowModels: () -> Unit,
    onShowQuestions: () -> Unit,
    onSendMessage: () -> Unit,
    input: String
) {
    Column(
        modifier = Modifier
            .width(300.dp)
            .fillMaxHeight()
            .background(MaterialTheme.colorScheme.surface)
            .padding(16.dp)
    ) {
        // Send Button with Input Preview
        if (input.isNotBlank()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                focusableLayout(
                    modifier = Modifier.weight(1f),
                    isClickable = false
                ) {
                    OutlinedTextField(
                        value = input,
                        onValueChange = {},
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("Type a message...") },
                        singleLine = true,
                        readOnly = true
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                focusableLayout(
                    modifier = Modifier
                        .width(80.dp)
                        .height(48.dp),
                    isClickable = true,
                    onClick = onSendMessage
                ) {
                    Button(
                        onClick = {},
                        modifier = Modifier.fillMaxSize()
                    ) {
                        Text("Send")
                    }
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
        } else {
            focusableLayout(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                isClickable = true,
                onClick = onSendMessage
            ) {
                OutlinedButton(
                    onClick = {},
                    modifier = Modifier.fillMaxSize()
                ) {
                    Text("Send")
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
        }

        // Models Button
        focusableLayout(
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp),
            isClickable = true,
            onClick = onShowModels
        ) {
            OutlinedButton(
                onClick = {},
                modifier = Modifier.fillMaxSize()
            ) {
                Text("Models")
            }
        }

        Spacer(Modifier.height(8.dp))

        // Questions Button
        focusableLayout(
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp),
            isClickable = true,
            onClick = onShowQuestions
        ) {
            OutlinedButton(
                onClick = {},
                modifier = Modifier.fillMaxSize()
            ) {
                Text("Questions")
            }
        }

        Spacer(Modifier.height(8.dp))

        // New Chat Button
        focusableLayout(
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp),
            isClickable = true,
            onClick = onNewConversation
        ) {
            OutlinedButton(
                onClick = {},
                modifier = Modifier.fillMaxSize()
            ) {
                Text("+ New Chat")
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Conversations List
        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(conversations) { conv ->
                val isSelected = conv.id == selectedConversationId

                focusableLayout(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(60.dp),
                    isClickable = true,
                    onClick = { onConversationSelected(conv.id) }
                ) {
                    OutlinedButton(
                        onClick = {},
                        modifier = Modifier.fillMaxSize(),
                        colors = ButtonDefaults.outlinedButtonColors(
                            containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer
                                           else MaterialTheme.colorScheme.surface
                        )
                    ) {
                        Text(
                            text = conv.title.ifBlank { "Chat ${conversations.indexOf(conv) + 1}" },
                            maxLines = 2
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun TVChatArea(
    messages: List<apps.visnkmr.batu.data.Message>,
    input: String,
    onInputChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    listState: androidx.compose.foundation.lazy.LazyListState
) {
    Column(
        modifier = modifier
            .fillMaxHeight()
            .padding(16.dp)
    ) {
        // Messages List
        LazyColumn(
            modifier = Modifier.weight(1f),
            state = listState,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            itemsIndexed(messages) { index, message ->
                TVMessageItem(message = message)
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Input Area
        focusableLayout(
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            isClickable = false
        ) {
            OutlinedTextField(
                value = input,
                onValueChange = onInputChange,
                modifier = Modifier.fillMaxSize(),
                placeholder = { Text("Type a message...") },
                singleLine = true
            )
        }
    }
}

@Composable
fun TVMessageItem(message: apps.visnkmr.batu.data.Message) {
    val isUser = message.role == "user"
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        Card(
            modifier = Modifier.widthIn(max = 400.dp),
            colors = CardDefaults.cardColors(
                containerColor = if (isUser) MaterialTheme.colorScheme.primary
                               else MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Text(
                text = message.content,
                modifier = Modifier.padding(16.dp),
                color = if (isUser) MaterialTheme.colorScheme.onPrimary
                       else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}