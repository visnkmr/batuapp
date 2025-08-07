package apps.visnkmr.batu.ui.llmchat

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.DrawerState
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDrawerState
import androidx.compose.foundation.layout.*
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.Checkbox
import androidx.compose.material3.TextButton
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import android.util.Log
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.focusable
import apps.visnkmr.batu.data.AppDatabase
import apps.visnkmr.batu.data.ChatRepository
import apps.visnkmr.batu.ui.common.chat.ChatMessage
import apps.visnkmr.batu.ui.common.chat.ChatMessageLoading
import apps.visnkmr.batu.ui.common.chat.ChatMessageText
import apps.visnkmr.batu.ui.common.chat.ChatSide
import apps.visnkmr.batu.ui.common.chat.ChatView
import apps.visnkmr.batu.ui.common.chat.ChatViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSource
import org.json.JSONArray
import org.json.JSONObject

/**
 * Clean working version:
 * - Plain in-memory ChatViewModel for UI state
 * - Persists via ChatRepository
 * - Streams via OpenRouter using SharedPreferences API key
 * - No Hilt
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BatuLlmChatScreen(
  navigateUp: () -> Unit,
  modifier: Modifier = Modifier
) {
  val context = LocalContext.current
  val TAG = "BatuLlmChat"
  val db = remember { AppDatabase.get(context) }
  val repo = remember { ChatRepository(db.conversationDao(), db.messageDao()) }

  var selectedConversationId by remember { mutableStateOf<Long?>(null) }
  val scope = rememberCoroutineScope()

  // Ensure there is a conversation to use
  LaunchedEffect(Unit) {
    if (selectedConversationId == null) {
      selectedConversationId = repo.newConversation()
    }
  }

  // Simple ChatViewModel in-memory (UI state); DB remains the source of truth for history
  val chatVm = remember { ChatViewModel() }
  // Left drawer + menu state (mirrors MainActivity’s pattern)
  val drawerState: DrawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
  var actionsExpanded by remember { mutableStateOf(false) }
  var showQuestions by remember { mutableStateOf(false) }
  var showModels by remember { mutableStateOf(false) }
  // Include history toggle next to input
  var includeHistory by remember { mutableStateOf(true) }
  // Auto-scroll state and detector
  var autoScroll by remember { mutableStateOf(true) }
  var loadingModels by remember { mutableStateOf(false) }
  var modelsError by remember { mutableStateOf<String?>(null) }
  var allModels by remember { mutableStateOf(listOf<String>()) }
  var freeModels by remember { mutableStateOf(setOf<String>()) }
  var filterFreeOnly by remember { mutableStateOf(false) }
  var searchQuery by remember { mutableStateOf("") }
  var visibleCount by remember { mutableStateOf(12) }

  // API key from SharedPreferences (same as MainActivity storage)
  val prefs = remember {
    context.getSharedPreferences("secure_prefs", android.content.Context.MODE_PRIVATE)
  }
  // Persisted API key editor
  var apiKey by remember { mutableStateOf(prefs.getString("openrouter_api_key", "") ?: "") }
  var selectedModel by remember { mutableStateOf("openrouter/auto") }

  // Conversations list in drawer
  val conversationsFlow = remember { repo.conversations() }
  val conversations by conversationsFlow.collectAsState(initial = emptyList())
  val messagesFlow = remember(selectedConversationId) {
    if (selectedConversationId != null) repo.messages(selectedConversationId!!) else null
  }
  val messagesInDb by (messagesFlow?.collectAsState(initial = emptyList())
    ?: remember { mutableStateOf(emptyList()) })
  val listState = rememberLazyListState()
  // Computed: are we near bottom?
  val isAtBottom by remember {
    androidx.compose.runtime.derivedStateOf {
      val lastIndex = (messagesInDb.size - 1).coerceAtLeast(0)
      val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
      lastVisible >= lastIndex - 1
    }
  }

  val client = remember {
    OkHttpClient.Builder()
      .retryOnConnectionFailure(true)
      .build()
  }

  // Simple model list fetcher (OpenRouter public list). Uses API key when present.
  fun parseFreeFlagFromName(name: String): Boolean {
    val lower = name.lowercase()
    return listOf("openrouter/auto","free","gemma","mistral","llama","qwen","mixtral","openhermes","phi-3","smollm").any { lower.contains(it) }
  }

  fun ensureModelsLoaded() {
    if (allModels.isNotEmpty() || loadingModels) return
    loadingModels = true
    modelsError = null
    scope.launch {
      try {
        val reqBuilder = okhttp3.Request.Builder()
          .url("https://openrouter.ai/api/v1/models")
          .get()
        if (apiKey.isNotBlank()) {
          reqBuilder.addHeader("Authorization", "Bearer $apiKey")
          reqBuilder.addHeader("HTTP-Referer", "https://example.com")
          reqBuilder.addHeader("X-Title", "Batu Chat")
        }
        val resp = withContext(Dispatchers.IO) { client.newCall(reqBuilder.build()).execute() }
        resp.use { r ->
          if (!r.isSuccessful) throw IllegalStateException("HTTP ${'$'}{r.code} ${'$'}{r.message}")
          val body = r.body?.string().orEmpty()
          val root = org.json.JSONObject(body)
          val data = root.optJSONArray("data") ?: org.json.JSONArray()
          val names = mutableListOf<String>()
          val freeSet = mutableSetOf<String>()
          for (i in 0 until data.length()) {
            val item = data.optJSONObject(i) ?: continue
            val idRaw = item.optString("id")
            if (idRaw.isNullOrBlank()) continue
            names.add(idRaw)
            val pricing = item.optJSONObject("pricing")
            val prompt = pricing?.opt("prompt")
            val completion = pricing?.opt("completion")
            val inputFree = prompt == null || prompt == org.json.JSONObject.NULL || (prompt is String && prompt.equals("0", true))
            val outputFree = completion == null || completion == org.json.JSONObject.NULL || (completion is String && completion.equals("0", true))
            if ((inputFree && outputFree) || parseFreeFlagFromName(idRaw)) {
              freeSet.add(idRaw)
            }
          }
          names.sort()
          allModels = names
          freeModels = freeSet
          visibleCount = 12
        }
      } catch (e: Exception) {
        modelsError = e.message ?: "Failed to load models"
      } finally {
        loadingModels = false
      }
    }
  }

  fun onSend(messages: List<ChatMessage>) {
    val conv = selectedConversationId ?: return
    // Add to UI and persist
    messages.forEach { msg ->
      when (msg) {
        is ChatMessageText -> {
          chatVm.addMessage(msg)
          scope.launch {
            repo.addUserMessage(conv, msg.content)
          }
        }
        else -> chatVm.addMessage(msg)
      }
    }

    val text = messages.filterIsInstance<ChatMessageText>().lastOrNull()?.content.orEmpty()
    if (text.isBlank()) return

    // Start streaming
    scope.launch {
      chatVm.setInProgress(true)
      // Add a loading bubble
      chatVm.addMessage(ChatMessageLoading())

      // Create assistant placeholder in DB and capture id
      val assistantId = repo.addAssistantPlaceholder(conv)

      try {
        withContext(Dispatchers.IO) {
          val url = "https://openrouter.ai/api/v1/chat/completions"
          val mediaType = "application/json".toMediaType()
          val history = if (includeHistory) (repo.messages(conv).firstOrNull() ?: emptyList()) else emptyList()

          val bodyJson = JSONObject().apply {
            put("model", selectedModel)
            put("stream", true)
            put("messages", JSONArray().apply {
              // Include up to last 40 messages from DB
              val last40 = history.takeLast(40)
              last40.forEach { m: apps.visnkmr.batu.data.Message ->
                put(JSONObject().apply {
                  put("role", m.role)
                  put("content", m.content)
                })
              }
              // Current user message is already in DB
            })
          }.toString()

          val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("HTTP-Referer", "https://example.com")
            .addHeader("X-Title", "Batu Chat")
            .post(bodyJson.toRequestBody(mediaType))
            .build()

          val response = client.newCall(request).execute()
          response.use { resp ->
            if (!resp.isSuccessful) {
              val errBody = try { resp.body?.string()?.take(400) } catch (_: Exception) { null }
              Log.e(TAG, "HTTP error ${'$'}{resp.code} ${'$'}{resp.message}. Body=${'$'}errBody")
              throw IllegalStateException("HTTP ${'$'}{resp.code} ${'$'}{resp.message}")
            } else {
              Log.d(TAG, "HTTP ${'$'}{resp.code} OK, streaming...")
            }
            val source: BufferedSource = resp.body?.source()
              ?: throw IllegalStateException("Empty body")

            var createdTextBubble = false
            while (isActive) {
              val rawLine = source.readUtf8Line() ?: break
              val line = rawLine.trim()
              if (line.isEmpty()) continue
              if (line.startsWith("data:", ignoreCase = true)) {
                val payload = line.substringAfter("data:", "").trim()
                if (payload == "[DONE]") break
                try {
                  val obj = JSONObject(payload)
                  val choices = obj.optJSONArray("choices")
                  val delta = if (choices != null && choices.length() > 0) {
                    choices.getJSONObject(0).optJSONObject("delta")?.optString("content").orEmpty()
                  } else {
                    obj.optJSONObject("message")?.optString("content").orEmpty()
                  }
                  if (delta.isNotEmpty()) {
                    withContext(Dispatchers.Main) {
                      // Replace loading with text bubble on first delta
                      if (!createdTextBubble) {
                        // Remove loading if last
                        chatVm.getLastMessage()?.let {
                          if (it is ChatMessageLoading) chatVm.removeLastMessage()
                        }
                        chatVm.addMessage(ChatMessageText("", side = ChatSide.AGENT))
                        createdTextBubble = true
                      }
                      // Append delta in UI
                      val last = chatVm.getLastMessage()
                      if (last is ChatMessageText && last.side == ChatSide.AGENT) {
                        last.content += delta
                      }
                    }
                    // Persist final content occasionally or at end; here we skip per-chunk DB writes
                  }
                } catch (_: Exception) {
                  // ignore malformed stream line
                }
              }
            }
          }
        }
      } catch (e: Exception) {
        Log.e(TAG, "Stream failed", e)
        // On error, replace loading with error message
        chatVm.getLastMessage()?.let {
          if (it is ChatMessageLoading) chatVm.removeLastMessage()
        }
        chatVm.addMessage(
          ChatMessageText(
            content = "[error] ${'$'}{e.message ?: \"\"}",
            side = ChatSide.AGENT
          )
        )
      } finally {
        chatVm.setInProgress(false)
        // Finalize DB content from UI last assistant bubble
        val last = chatVm.getLastMessage()
        if (last is ChatMessageText && last.side == ChatSide.AGENT) {
          // Persist final generated content
          scope.launch(Dispatchers.IO) {
            repo.updateMessageContent(assistantId, last.content)
          }
        }
      }
    }
  }

  fun onRunAgain(message: ChatMessageText) {
    // Clone user message and send again
    onSend(listOf(ChatMessageText(message.content, side = ChatSide.USER)))
  }

  fun onResetSession() {
    chatVm.setIsResettingSession(true)
    chatVm.clearAllMessages()
    chatVm.setIsResettingSession(false)
  }

  fun onStop() {
    // OpenRouter HTTP call can't be canceled without tracking the Call; out of scope here.
    // For now just flip the UI state.
    chatVm.setInProgress(false)
  }

  // Wrap content in a ModalNavigationDrawer to provide left menu (Conversations + Settings)
  ModalNavigationDrawer(
    drawerState = drawerState,
    drawerContent = {
      ModalDrawerSheet {
        // Make left sidebar scrollable and DPAD navigable
        val drawerListState = rememberLazyListState()
        LazyColumn(
          state = drawerListState,
          modifier = Modifier
            .fillMaxHeight()
            .width(280.dp)
            .padding(12.dp)
            .focusable(),
          verticalArrangement = Arrangement.Top
        ) {
          item {
            Text("Conversations", style = androidx.compose.material3.MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            HorizontalDivider()
            Spacer(Modifier.height(8.dp))
          }
          itemsIndexed(conversations) { _, conv ->
            NavigationDrawerItem(
              label = { Text(conv.title) },
              selected = conv.id == selectedConversationId,
              onClick = { selectedConversationId = conv.id },
              colors = NavigationDrawerItemDefaults.colors(),
              modifier = Modifier
                .fillMaxWidth()
                .focusable()
            )
          }
          item { Spacer(Modifier.height(12.dp)) }
          item {
            TextButton(
              onClick = {
                scope.launch {
                  selectedConversationId = repo.newConversation()
                }
              },
              modifier = Modifier.focusable()
            ) { Text("New chat") }
          }
          item {
            Spacer(Modifier.height(12.dp))
            HorizontalDivider()
            Spacer(Modifier.height(12.dp))
            Text("Settings", style = androidx.compose.material3.MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
          }
          item {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
              OutlinedTextField(
                value = apiKey,
                onValueChange = { apiKey = it },
                placeholder = { Text("sk-or-v1-...") },
                singleLine = true,
                label = {
                  // Force single-line label so it never wraps
                  Text(
                    text = "OpenRouter API Key",
                    maxLines = 1
                  )
                },
                modifier = Modifier
                  .weight(1f)
                  .focusable()
              )
              Spacer(Modifier.width(8.dp))
              TextButton(
                onClick = {
                  prefs.edit().putString("openrouter_api_key", apiKey.trim()).apply()
                },
                modifier = Modifier.focusable()
              ) { Text("Save") }
            }
            Spacer(Modifier.height(8.dp))
          }
          item {
            // Secondary trigger for Models inside drawer
            TextButton(
              onClick = {
                ensureModelsLoaded()
                showModels = true
              },
              modifier = Modifier.focusable()
            ) { Text("Models") }
          }
          item { Spacer(Modifier.height(24.dp)) }
        }
      }
    }
  ) {
    Scaffold(
      topBar = {
        CenterAlignedTopAppBar(
          title = { Text("Batu Chat") },
          navigationIcon = {
            // Left hamburger menu (open drawer)
            IconButton(
            onClick = {
              if (showQuestions) showQuestions = false
              scope.launch { drawerState.open() }
            },
            modifier = Modifier.focusable()
          ) {
            Icon(Icons.Filled.Menu, contentDescription = "Menu")
          }
          },
          actions = {
            // Actions dropdown (Models, Questions)
            Box {
              IconButton(
                onClick = { actionsExpanded = !actionsExpanded },
                modifier = Modifier.focusable()
              ) { Text("🤖") }
              DropdownMenu(expanded = actionsExpanded, onDismissRequest = { actionsExpanded = false }) {
                DropdownMenuItem(
                  text = { Text("Questions") },
                  onClick = {
                    actionsExpanded = false
                    showQuestions = true
                  }
                )
                DropdownMenuItem(
                  text = { Text("Models") },
                  onClick = {
                    actionsExpanded = false
                    ensureModelsLoaded()
                    showModels = true
                  }
                )
                DropdownMenuItem(
                  text = { Text("New chat") },
                  onClick = {
                    actionsExpanded = false
                    scope.launch {
                      selectedConversationId = repo.newConversation()
                    }
                  }
                )
              }
            }
          },
          colors = TopAppBarDefaults.centerAlignedTopAppBarColors()
        )
      }
    ) { innerPadding ->
      // Main chat content
      Box(modifier = modifier
        .fillMaxSize()
        .padding(innerPadding)
        .focusable()) {
        // ChatView + input adornments row
        Column(modifier = Modifier
          .fillMaxSize()
          .padding(12.dp)) {
          // Messages area with per-message actions row below each bubble (copy/resend/branch)
          androidx.compose.foundation.lazy.LazyColumn(
            state = listState,
            modifier = Modifier
              .weight(1f)
              .fillMaxWidth()
              .focusable()
          ) {
            // Render messages from repo snapshot for parity and attach action row
            itemsIndexed(messagesInDb) { _, m ->
              // Use ChatViewModel's own bubbles by syncing separately, but here we render action row under DB message
              // Bubble content itself is managed by ChatView below; we show only actions as lightweight row
              // For simplicity, only action row is shown here; bubbles are drawn by ChatView after this list.
              Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                  .fillMaxWidth()
                  .padding(vertical = 2.dp)
              ) {
                // Actions below bubble
                Spacer(Modifier.width(4.dp))
                TextButton(
                  onClick = {
                    val cb = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    cb.setPrimaryClip(android.content.ClipData.newPlainText("message", m.content))
                  },
                  modifier = Modifier.focusable()
                ) { Text("⧉") }
                if (m.role == "user") {
                  TextButton(
                    onClick = {
                      val conv = selectedConversationId ?: return@TextButton
                      scope.launch {
                        repo.addUserMessage(conv, m.content)
                        onSend(listOf(ChatMessageText(m.content, side = ChatSide.USER)))
                      }
                    },
                    modifier = Modifier.focusable()
                  ) { Text("↻") }
                  TextButton(
                    onClick = {
                      scope.launch {
                        val newId = repo.branchFromMessage(m.id)
                        if (newId > 0) {
                          selectedConversationId = newId
                        }
                      }
                    },
                    modifier = Modifier.focusable()
                  ) { Text("⎘") }
                }
              }
            }
          }

          // Core ChatView input/output (keeps its internal layout for composing/send row)
          ChatView(
            viewModel = chatVm,
            onSendMessage = { msgs -> onSend(msgs) },
            onRunAgainClicked = { msg -> onRunAgain(msg) },
            onResetSessionClicked = { onResetSession() },
            showStopButtonInInputWhenInProgress = true,
            onStopButtonClicked = { onStop() },
            // Render include-history toggle inline on the right side of input via trailing content, if supported.
            // If unsupported, we add a small row under input:
            modifier = Modifier
              .fillMaxWidth()
              .focusable()
          )

          // Row under input: include history toggle + legend for send/reset icons
          Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
              .fillMaxWidth()
              .padding(top = 6.dp)
          ) {
            // Include history toggle
            TextButton(
              onClick = { includeHistory = !includeHistory },
              modifier = Modifier.focusable()
            ) {
              Text(if (includeHistory) "H✓" else "H")
            }
            Spacer(Modifier.width(8.dp))
            // Legend for glyphs (optional)
            Text("➤ send   ⟲ reset", color = Color(0xFF888888))
          }
        }
        // Floating auto-scroll FAB
        val showFab = !isAtBottom || !autoScroll
        if (showFab) {
          androidx.compose.material3.FloatingActionButton(
            onClick = {
              if (!isAtBottom) {
                scope.launch {
                  val last = (messagesInDb.size - 1).coerceAtLeast(0)
                  listState.scrollToItem(last)
                }
                autoScroll = true
              } else {
                autoScroll = !autoScroll
              }
            },
            modifier = Modifier
              .align(Alignment.BottomEnd)
              .padding(12.dp)
              .focusable()
          ) {
            Text(if (!isAtBottom) "↓" else if (autoScroll) "✓" else "✕")
          }
        }

        // Questions dialog — list user messages, jump-to when tapped
        if (showQuestions) {
          androidx.compose.material3.AlertDialog(
            onDismissRequest = { showQuestions = false },
            confirmButton = {
              TextButton(onClick = { showQuestions = false }, modifier = Modifier.focusable()) { Text("Close") }
            },
            title = { Text("Questions in this thread") },
            text = {
              Column(
                modifier = Modifier
                  .fillMaxWidth()
                  .height(320.dp)
              ) {
                HorizontalDivider()
                Spacer(Modifier.height(8.dp))
                val questionItems = messagesInDb.mapIndexedNotNull { idx, m ->
                  if (m.role == "user") Pair(idx, m) else null
                }
                LazyColumn(
                  state = listState,
                  modifier = Modifier.fillMaxHeight()
                ) {
                  itemsIndexed(questionItems) { _, pair ->
                    val (idx, msg) = pair
                    Row(
                      verticalAlignment = Alignment.CenterVertically,
                      modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 6.dp)
                    ) {
                      TextButton(
                        onClick = {
                          scope.launch {
                            listState.scrollToItem(idx)
                            showQuestions = false
                          }
                        },
                        modifier = Modifier.focusable()
                      ) { Text("▶") }
                      Spacer(Modifier.width(8.dp))
                      Text(msg.content.take(120))
                    }
                    HorizontalDivider()
                  }
                }
              }
            }
          )
        }
        // Models dialog
        if (showModels) {
          androidx.compose.material3.AlertDialog(
            onDismissRequest = { showModels = false },
            confirmButton = {
              TextButton(onClick = { showModels = false }, modifier = Modifier.focusable()) { Text("Close") }
            },
            title = { Text("Select Model") },
            text = {
              Column(modifier = Modifier.fillMaxWidth()) {
                if (loadingModels) {
                  Text("Loading models…")
                  Spacer(Modifier.height(8.dp))
                }
                modelsError?.let { err ->
                  Text("Error: ${'$'}err", color = Color(0xFFB00020))
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
                HorizontalDivider()
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
                    .height(260.dp)
                ) {
                  itemsIndexed(toShow) { _, modelName ->
                    Row(
                      verticalAlignment = Alignment.CenterVertically,
                      modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 6.dp)
                    ) {
                      val isFree = freeModels.contains(modelName)
                      TextButton(onClick = {
                        selectedModel = modelName
                        showModels = false
                      }) { Text("✓") }
                      Spacer(Modifier.width(8.dp))
                      Text(modelName, modifier = Modifier.weight(1f))
                      if (isFree) {
                        Spacer(Modifier.width(6.dp))
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
                  TextButton(
                    onClick = {
                      val filteredSize = filtered.size
                      visibleCount = (visibleCount + 12).coerceAtMost(filteredSize)
                    },
                    modifier = Modifier.focusable()
                  ) {
                    Text("＋12")
                  }
                }
              }
            }
          )
        }
      }
    }
  }
}
