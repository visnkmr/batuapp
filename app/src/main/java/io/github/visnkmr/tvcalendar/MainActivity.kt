package apps.visnkmr.batu

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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Divider
import androidx.compose.material3.ElevatedButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.MutableState
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
                    ChatScreen(
                        context = this,
                        prefs = prefs,
                        dark = dark,
                        onToggleDark = { dark = !dark }
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
    onToggleDark: () -> Unit
) {
    // Messages kept in memory for this session
    var messages by remember { mutableStateOf(listOf<Pair<Boolean, String>>()) } // Pair(isUser, text)
    var input by remember { mutableStateOf("") }
    var showKeyDialog by remember { mutableStateOf(false) }
    var apiKey by remember { mutableStateOf(prefs.getString("openrouter_api_key", "") ?: "") }
    val scope = rememberCoroutineScope()

    // Models dropdown state
    var showModels by remember { mutableStateOf(false) }
    var allModels by remember { mutableStateOf(listOf<String>()) }
    var freeModels by remember { mutableStateOf(setOf<String>()) }
    var visibleCount by remember { mutableStateOf(5) }
    var loadingModels by remember { mutableStateOf(false) }
    var modelsError by remember { mutableStateOf<String?>(null) }
    var filterFreeOnly by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var selectedModel by remember { mutableStateOf("openrouter/auto") }

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

    fun appendMessage(isUser: Boolean, text: String) {
        messages = messages + (isUser to text)
    }

    fun updateLastAssistant(delta: String) {
        val idx = messages.indexOfLast { !it.first } // last assistant
        if (idx == -1) {
            // create new assistant item
            messages = messages + (false to delta)
        } else {
            val m = messages.toMutableList()
            m[idx] = false to (m[idx].second + delta)
            messages = m.toList()
        }
    }

    suspend fun streamChat(prompt: String) {
        withContext(Dispatchers.IO) {
            val url = "https://openrouter.ai/api/v1/chat/completions"
            val mediaType = "application/json".toMediaType()
            // Build minimal JSON; we use org.json already on Android
            val bodyJson = JSONObject().apply {
                put("model", selectedModel)
                put("stream", true)
                put("messages", JSONArray().apply {
                    put(JSONObject().apply {
                        put("role", "system")
                        put("content", "You are a helpful assistant.")
                    })
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
                                        updateLastAssistant(delta)
                                    }
                                }
                            } else {
                                // Some providers send "message" full chunks
                                val msg = obj.optJSONObject("message")
                                val content = msg?.optString("content").orEmpty()
                                if (content.isNotEmpty()) {
                                    withContext(Dispatchers.Main) {
                                        updateLastAssistant(content)
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
            Text(
                text = "Batu Chat",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f)
            )
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            // Model picker trigger shows selected model name
            OutlinedButton(onClick = {
                ensureModelsLoaded()
                showModels = true
            }) {
                Text(selectedModel.take(22) + if (selectedModel.length > 22) "…" else "")
            }
            Spacer(Modifier.widthIn(8.dp))
            OutlinedButton(onClick = onToggleDark) {
                Text(if (dark) "Light" else "Dark")
            }
            Spacer(Modifier.widthIn(8.dp))
            OutlinedButton(onClick = { showKeyDialog = true }) {
                Text(if (apiKey.isBlank()) "Set API Key" else "Update Key")
            }
        }

        Spacer(Modifier.height(8.dp))

        // Messages list
        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            reverseLayout = false
        ) {
            items(messages) { (isUser, text) ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
                ) {
                    val bubbleColor = if (isUser) Color(0xFF007AFF) else MaterialTheme.colorScheme.surfaceVariant
                    val textColor = if (isUser) Color.White else MaterialTheme.colorScheme.onSurface
                    Box(
                        modifier = Modifier
                            .widthIn(max = 280.dp)
                            .background(bubbleColor, shape = MaterialTheme.shapes.medium)
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                    ) {
                        Text(text, color = textColor)
                    }
                }
            }
            item { Spacer(modifier = Modifier.height(60.dp)) }
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
                        if (trimmed.isNotEmpty()) {
                            if (apiKey.isBlank()) {
                                showKeyDialog = true
                                return@ElevatedButton
                            }
                            appendMessage(true, trimmed)
                            // Placeholder assistant entry for streaming
                            appendMessage(false, "")
                            val userPrompt = trimmed
                            input = ""
                            scope.launch {
                                try {
                                    streamChat(userPrompt)
                                } catch (ce: CancellationException) {
                                    // ignore
                                } catch (e: Exception) {
                                    withContext(Dispatchers.Main) {
                                        updateLastAssistant("\n[error] ${e.message}")
                                    }
                                }
                            }
                        }
                    },
                ) {
                    Text("Send")
                }
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

    if (showKeyDialog) {
        var tempKey by remember { mutableStateOf(apiKey) }
        AlertDialog(
            onDismissRequest = { showKeyDialog = false },
            title = { Text("OpenRouter API Key") },
            text = {
                Column {
                    Text("Enter your OpenRouter API key. It will be stored encrypted on device.")
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = tempKey,
                        onValueChange = { tempKey = it },
                        singleLine = true,
                        placeholder = { Text("sk-or-v1-...") }
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    apiKey = tempKey.trim()
                    prefs.edit().putString("openrouter_api_key", apiKey).apply()
                    showKeyDialog = false
                }) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { showKeyDialog = false }) { Text("Cancel") }
            }
        )
    }
}
