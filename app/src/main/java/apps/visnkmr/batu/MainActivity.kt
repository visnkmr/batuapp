package apps.visnkmr.batu

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import android.annotation.SuppressLint
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Divider
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ElevatedButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import apps.visnkmr.batu.ui.theme.TVCalendarTheme
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import apps.visnkmr.batu.data.AppDatabase
import apps.visnkmr.batu.data.ChatRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSource
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

@SuppressLint("CustomSplashScreen")
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Build encrypted preferences for storing the OpenRouter API key
        val masterKey = MasterKey.Builder(this)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        val prefs = EncryptedSharedPreferences.create(
            this,
            "secure_prefs",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )

        setContent {
            var dark by remember { mutableStateOf(true) }

            TVCalendarTheme(darkTheme = dark) {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    val db = remember { AppDatabase.get(this) }
                    val repo = remember { ChatRepository(db.conversationDao(), db.messageDao()) }
                    ChatScreen(
                        context = this,
                        prefs = prefs,
                        dark = dark,
                        onToggleDark = { dark = !dark },
                        repo = repo
                    )
                }
            }
        }
    }
}

@Composable
fun ChatScreen(
    context: Context,
    prefs: android.content.SharedPreferences,
    dark: Boolean,
    onToggleDark: () -> Unit,
    repo: ChatRepository
) {
    val scope = rememberCoroutineScope()

    // Drawer state and API key moved into drawer
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    var apiKey by remember { mutableStateOf(prefs.getString("openrouter_api_key", "") ?: "") }

    // Conversations and selection
    val conversations by repo.conversations().collectAsState(initial = emptyList())
    var selectedConversationId by remember { mutableStateOf<Long?>(null) }

    LaunchedEffect(conversations) {
        if (selectedConversationId == null) {
            // Create a conversation if none exists
            if (conversations.isEmpty()) {
                selectedConversationId = repo.newConversation()
            } else {
                selectedConversationId = conversations.first().id
            }
        }
    }

    // Messages for selected conversation
    val messagesFlow = remember(selectedConversationId) {
        if (selectedConversationId != null) repo.messages(selectedConversationId!!) else null
    }
    val messagesInDb by (messagesFlow?.collectAsState(initial = emptyList()) ?: remember { mutableStateOf(emptyList()) })

    // Input and UI
    var input by remember { mutableStateOf("") }
    var showModels by remember { mutableStateOf(false) }
    var allModels by remember { mutableStateOf(listOf<String>()) }
    var freeModels by remember { mutableStateOf(setOf<String>()) }
    var visibleCount by remember { mutableStateOf(5) }
    var loadingModels by remember { mutableStateOf(false) }
    var modelsError by remember { mutableStateOf<String?>(null) }
    var filterFreeOnly by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var selectedModel by remember { mutableStateOf("openrouter/auto") }
    val listState = rememberLazyListState()

    // OkHttp client
    val client = remember {
        OkHttpClient.Builder()
            // Important for streaming: don't buffer entire body
            .retryOnConnectionFailure(true)
            .build()
    }

    // Load models lazily on first open of dropdown
    fun parseFreeFlagFromName(name: String): Boolean {
        // Heuristic fallback if API doesn't provide price metadata
        val lower = name.lowercase()
        return listOf("openrouter/auto", "free", "gemma", "mistral", "llama", "qwen", "mixtral", "openhermes", "phi-3", "smollm").any { lower.contains(it) }
    }

    fun ensureModelsLoaded() {
        if (allModels.isNotEmpty() || loadingModels) return
        loadingModels = true
        modelsError = null
        scope.launch {
            try {
                // Fetch models list (use API key when available for full metadata/access)
                val reqBuilder = Request.Builder()
                    .url("https://openrouter.ai/api/v1/models")
                    .get()
                if (apiKey.isNotBlank()) {
                    reqBuilder.addHeader("Authorization", "Bearer $apiKey")
                    // Optional but recommended per OpenRouter guidelines:
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
                        if (idRaw.isNullOrBlank()) {
                            continue
                        }
                        val id = idRaw
                        names.add(id)
                        // Prefer explicit pricing metadata when available
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
                    names.sort()
                    allModels = names
                    freeModels = freeSet
                    // Reset visible count whenever we load anew
                    visibleCount = 5
                }
            } catch (e: Exception) {
                modelsError = e.message ?: "Failed to load models"
            } finally {
                loadingModels = false
            }
        }
    }

    suspend fun updateAssistantStreaming(messageId: Long, delta: String) {
        val m = messagesInDb.find { it.id == messageId } ?: return
        repo.updateMessageContent(messageId, m.content + delta)
    }

    suspend fun streamChat(conversationId: Long, prompt: String) {
        // create assistant message placeholder and capture ID for streaming updates
        val assistantId = repo.addAssistantPlaceholder(conversationId)
        withContext(Dispatchers.IO) {
            val url = "https://openrouter.ai/api/v1/chat/completions"
            val mediaType = "application/json".toMediaType()
            // Build minimal JSON; we use org.json already on Android
            val bodyJson = JSONObject().apply {
                put("model", selectedModel)
                put("stream", true)
                put("messages", JSONArray().apply {
                    // Reconstruct conversation history from DB (last 40 messages)
                    val history = messagesInDb.takeLast(40)
                    history.forEach { msg ->
                        put(JSONObject().apply {
                            put("role", msg.role)
                            put("content", msg.content)
                        })
                    }
                    // Append current user prompt
                    put(JSONObject().apply {
                        put("role", "user")
                        put("content", prompt)
                    })
                })
            }.toString()
            val request = Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer $apiKey")
                .addHeader("HTTP-Referer", "https://example.com")
                .addHeader("X-Title", "Batu Chat")
                .post(bodyJson.toRequestBody(mediaType))
                .build()

            val call = client.newCall(request)
            val response = call.execute()
            response.use { resp ->
                if (!resp.isSuccessful) {
                    throw IllegalStateException("HTTP ${resp.code} ${resp.message}")
                }
                val source: BufferedSource = resp.body?.source()
                    ?: throw IllegalStateException("Empty body")
                // Ensure we treat as a stream
                while (true) {
                    if (!isActive) break
                    val rawLine = source.readUtf8Line() ?: break
                    val line = rawLine.trim()
                    if (line.isEmpty()) {
                        // skip blanks -- do nothing
                    } else if (line.startsWith("data:", ignoreCase = true)) {
                        // OpenRouter streams "data: {json}" lines
                        val payload = line.substringAfter("data:", "").trim()
                        if (payload == "[DONE]") {
                            break
                        }
                        try {
                            val obj = JSONObject(payload)
                            val choices = obj.optJSONArray("choices")
                            if (choices != null && choices.length() > 0) {
                                val delta = choices.getJSONObject(0)
                                    .optJSONObject("delta")
                                    ?.optString("content") ?: ""
                                if (delta.isNotEmpty()) {
                                    withContext(Dispatchers.Main) {
                                        updateAssistantStreaming(assistantId, delta)
                                    }
                                }
                            } else {
                                // Some providers send "message" full chunks
                                val msg = obj.optJSONObject("message")
                                val content = msg?.optString("content").orEmpty()
                                if (content.isNotEmpty()) {
                                    withContext(Dispatchers.Main) {
                                        updateAssistantStreaming(assistantId, content)
                                    }
                                }
                            }
                        } catch (_: Exception) {
                            // Ignore malformed lines
                        }
                    }
                }
            }
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            Column(
                modifier = Modifier
                    .fillMaxHeight()
                    .width(280.dp)
                    .padding(12.dp)
            ) {
                Text("Conversations", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                ElevatedButton(onClick = {
                    scope.launch {
                        selectedConversationId = repo.newConversation()
                    }
                }) { Text("New chat") }
                Spacer(Modifier.height(8.dp))
                Divider()
                Spacer(Modifier.height(8.dp))
                conversations.forEach { conv ->
                    NavigationDrawerItem(
                        label = { Text(conv.title) },
                        selected = conv.id == selectedConversationId,
                        onClick = { selectedConversationId = conv.id },
                        colors = NavigationDrawerItemDefaults.colors()
                    )
                }
                Spacer(Modifier.height(12.dp))
                Divider()
                Spacer(Modifier.height(12.dp))
                Text("Settings", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it },
                    placeholder = { Text("sk-or-v1-...") },
                    singleLine = true,
                    label = { Text("OpenRouter API Key") }
                )
                Spacer(Modifier.height(6.dp))
                OutlinedButton(onClick = {
                    prefs.edit().putString("openrouter_api_key", apiKey.trim()).apply()
                }) { Text("Save API Key") }
                Spacer(Modifier.height(12.dp))
                OutlinedButton(onClick = onToggleDark) {
                    Text(if (dark) "Switch to Light" else "Switch to Dark")
                }
            }
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(12.dp)
        ) {
            // Header
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                IconButton(onClick = { scope.launch { drawerState.open() } }) {
                    Icon(imageVector = Icons.Filled.Menu, contentDescription = "Menu")
                }
                Text(
                    text = "Batu Chat",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f)
                )
                OutlinedButton(onClick = {
                    ensureModelsLoaded()
                    showModels = true
                }) {
                    Text(selectedModel.take(22) + if (selectedModel.length > 22) "…" else "")
                }
                Spacer(Modifier.width(8.dp))
                ElevatedButton(onClick = {
                    scope.launch { selectedConversationId = repo.newConversation() }
                }) { Text("New chat") }
            }

        Spacer(Modifier.height(8.dp))

        Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
            // Center: messages
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
                reverseLayout = false
            ) {
                itemsIndexed(messagesInDb) { index, msg ->
                    val isUser = msg.role == "user"
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
                    ) {
                        val bubbleColor = if (isUser) Color(0xFF007AFF) else MaterialTheme.colorScheme.surfaceVariant
                        val textColor = if (isUser) Color.White else MaterialTheme.colorScheme.onSurface
                        Column(horizontalAlignment = if (isUser) Alignment.End else Alignment.Start) {
                            Box(
                                modifier = Modifier
                                    .widthIn(max = 280.dp)
                                    .background(bubbleColor, shape = MaterialTheme.shapes.medium)
                                    .padding(horizontal = 12.dp, vertical = 8.dp)
                            ) {
                                Text(msg.content, color = textColor)
                            }
                            // Actions row
                            Row {
                                TextButton(onClick = {
                                    val cb = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    cb.setPrimaryClip(ClipData.newPlainText("message", msg.content))
                                }) { Text("Copy") }
                                if (isUser) {
                                    TextButton(onClick = {
                                        // Resend same prompt in current conversation
                                        if (selectedConversationId != null) {
                                            scope.launch {
                                                val conv = selectedConversationId!!
                                                repo.addUserMessage(conv, msg.content)
                                                val assistantId = repo.addAssistantPlaceholder(conv)
                                                try {
                                                    streamChat(conv, msg.content)
                                                } catch (e: Exception) {
                                                    repo.updateMessageContent(assistantId, "[error] ${e.message}")
                                                }
                                            }
                                        }
                                    }) { Text("Resend") }
                                    TextButton(onClick = {
                                        // Branch from this message
                                        scope.launch {
                                            val newId = repo.branchFromMessage(msg.id)
                                            if (newId > 0) {
                                                selectedConversationId = newId
                                            }
                                        }
                                    }) { Text("Branch") }
                                }
                            }
                        }
                    }
                }
                item { Spacer(modifier = Modifier.height(60.dp)) }
            }

            // Right: Similar questions panel
            Column(
                modifier = Modifier
                    .width(220.dp)
                    .fillMaxHeight()
                    .padding(start = 8.dp)
            ) {
                Text("Similar questions", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                val candidates = messagesInDb.filter { it.role == "user" }.reversed().let { list ->
                    val q = input.trim()
                    if (q.isBlank()) list.take(10) else list.filter { it.content.contains(q, ignoreCase = true) }.take(10)
                }
                LazyColumn {
                    items(candidates.size) { i ->
                        val m = candidates[i]
                        TextButton(onClick = {
                            val idx = messagesInDb.indexOfFirst { it.id == m.id }
                            if (idx >= 0) {
                                scope.launch { listState.animateScrollToItem(idx) }
                            }
                        }) {
                            Text(m.content.take(60))
                        }
                    }
                }
            }
        }

        // Bottom input bar
        Box(
            modifier = Modifier
                .fillMaxWidth()
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
            ) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    modifier = Modifier
                        .weight(1f)
                        .padding(end = 8.dp),
                    singleLine = true,
                    placeholder = { Text("Send a message") }
                )
                ElevatedButton(
                    onClick = {
                        val trimmed = input.trim()
                        val conv = selectedConversationId
                        if (trimmed.isNotEmpty() && conv != null) {
                            if (apiKey.isBlank()) {
                                scope.launch { drawerState.open() }
                                return@ElevatedButton
                            }
                            val userPrompt = trimmed
                            input = ""
                            scope.launch {
                                try {
                                    repo.addUserMessage(conv, userPrompt)
                                    streamChat(conv, userPrompt)
                                } catch (ce: CancellationException) {
                                    // ignore
                                } catch (e: Exception) {
                                    // error already handled in streamChat by updating assistant message
                                }
                            }
                        }
                    },
                ) { Text("Send") }
            }
        }
    }

    if (showModels) {
        AlertDialog(
            onDismissRequest = { showModels = false },
            title = { Text("Select Model") },
            text = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    // Loading / error
                    if (loadingModels) {
                        Text("Loading models…")
                        Spacer(Modifier.height(8.dp))
                    }
                    modelsError?.let { err ->
                        Text("Error: $err", color = Color(0xFFB00020))
                        Spacer(Modifier.height(8.dp))
                    }

                    // Search box
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        singleLine = true,
                        placeholder = { Text("Search models") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))

                    // Free-only filter
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = filterFreeOnly, onCheckedChange = { filterFreeOnly = it })
                        Text("Free models only")
                    }

                    Spacer(Modifier.height(8.dp))
                    Divider()
                    Spacer(Modifier.height(8.dp))

                    // Filtered list, paginated 5 at a time
                    val filtered = allModels.filter { m ->
                        val okFree = if (filterFreeOnly) freeModels.contains(m) else true
                        val okSearch = if (searchQuery.isNotBlank()) m.contains(searchQuery, ignoreCase = true) else true
                        okFree && okSearch
                    }
                    val toShow = filtered.take(visibleCount)

                    // Scrollable list with vertical scroll
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(220.dp)
                            .verticalScroll(rememberScrollState())
                    ) {
                        toShow.forEach { m ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 6.dp)
                            ) {
                                val isFree = freeModels.contains(m)
                                OutlinedButton(onClick = {
                                    selectedModel = m
                                    showModels = false
                                }) {
                                    Text(m)
                                }
                                if (isFree) {
                                    Spacer(Modifier.widthIn(6.dp))
                                    Text("FREE", color = Color(0xFF2E7D32))
                                }
                            }
                        }
                        if (toShow.isEmpty()) {
                            Text("No models found")
                        }
                    }

                    Spacer(Modifier.height(8.dp))

                    // Show more button
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        OutlinedButton(onClick = {
                            // Increase by 5 at a time, but not beyond filtered size
                            val filteredSize = filtered.size
                            visibleCount = (visibleCount + 5).coerceAtMost(filteredSize)
                        }) {
                            Text("Show 5 more")
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showModels = false }) { Text("Close") }
            }
        )
    }

    }
    // Models dialog retained as before
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
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        singleLine = true,
                        placeholder = { Text("Search models") },
                        modifier = Modifier.fillMaxWidth()
                    )
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
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(220.dp)
                            .verticalScroll(rememberScrollState())
                    ) {
                        toShow.forEach { m ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 6.dp)
                            ) {
                                val isFree = freeModels.contains(m)
                                OutlinedButton(onClick = {
                                    selectedModel = m
                                    showModels = false
                                }) {
                                    Text(m)
                                }
                                if (isFree) {
                                    Spacer(Modifier.widthIn(6.dp))
                                    Text("FREE", color = Color(0xFF2E7D32))
                                }
                            }
                        }
                        if (toShow.isEmpty()) {
                            Text("No models found")
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        OutlinedButton(onClick = {
                            val filteredSize = filtered.size
                            visibleCount = (visibleCount + 5).coerceAtMost(filteredSize)
                        }) {
                            Text("Show 5 more")
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showModels = false }) { Text("Close") }
            }
        )
    }
}
