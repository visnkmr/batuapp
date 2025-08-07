package apps.visnkmr.batu.ui.common.chat

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

// Basic enums and data used by the chat UI

enum class ChatSide { USER, AGENT }
enum class ChatMessageType { TEXT, IMAGE, AUDIO, LOADING, WARNING }

data class Stat(
  val id: String,
  val label: String,
  val unit: String
)

data class ChatMessageBenchmarkLlmResult(
  val orderedStats: List<Stat>,
  val statValues: MutableMap<String, Float>,
  val running: Boolean,
  val latencyMs: Float,
  val accelerator: String? = null
)

sealed interface ChatMessage {
  val type: ChatMessageType
  val side: ChatSide
  val accelerator: String?
}

data class ChatMessageText(
  var content: String,
  override val side: ChatSide,
  override val accelerator: String? = null,
  var llmBenchmarkResult: ChatMessageBenchmarkLlmResult? = null
) : ChatMessage {
  override val type: ChatMessageType = ChatMessageType.TEXT
  fun clone(): ChatMessageText = copy()
}

data class ChatMessageImage(
  val bitmaps: List<Bitmap>,
  override val side: ChatSide,
  override val accelerator: String? = null
) : ChatMessage {
  override val type: ChatMessageType = ChatMessageType.IMAGE
}

data class ChatMessageAudioClip(
  val bytes: ByteArray,
  val sampleRateHz: Int,
  override val side: ChatSide,
  override val accelerator: String? = null
) : ChatMessage {
  override val type: ChatMessageType = ChatMessageType.AUDIO
  fun getDurationInSeconds(): Float = bytes.size.toFloat() / (sampleRateHz * 2f) // rough
  fun genByteArrayForWav(): ByteArray = bytes
}

data class ChatMessageLoading(
  override val side: ChatSide = ChatSide.AGENT,
  override val accelerator: String? = null
) : ChatMessage {
  override val type: ChatMessageType = ChatMessageType.LOADING
}

data class ChatMessageWarning(
  val content: String,
  override val side: ChatSide = ChatSide.AGENT,
  override val accelerator: String? = null
) : ChatMessage {
  override val type: ChatMessageType = ChatMessageType.WARNING
}

// Simple ChatViewModel managing messages per "active model" (here just a single session)
open class ChatViewModel {
  private val _messages = mutableStateListOf<ChatMessage>()
  val messages: List<ChatMessage> get() = _messages

  private var _inProgress by mutableStateOf(false)
  val inProgress: Boolean get() = _inProgress

  private var _preparing by mutableStateOf(false)
  val preparing: Boolean get() = _preparing

  private var _isResetting by mutableStateOf(false)
  val isResetting: Boolean get() = _isResetting

  fun addMessage(message: ChatMessage) {
    _messages.add(message)
  }

  fun clearAllMessages() {
    _messages.clear()
  }

  fun removeLastMessage() {
    if (_messages.isNotEmpty()) {
      _messages.removeAt(_messages.lastIndex)
    }
  }

  fun getLastMessage(): ChatMessage? = _messages.lastOrNull()

  fun updateLastTextMessageContentIncrementally(partialContent: String, latencyMs: Float) {
    val last = _messages.lastOrNull()
    if (last is ChatMessageText) {
      last.content += partialContent
    }
  }

  fun updateLastTextMessageLlmBenchmarkResult(llmBenchmarkResult: ChatMessageBenchmarkLlmResult) {
    val last = _messages.lastOrNull()
    if (last is ChatMessageText) {
      last.llmBenchmarkResult = llmBenchmarkResult
    }
  }

  fun setInProgress(v: Boolean) { _inProgress = v }
  fun setPreparing(v: Boolean) { _preparing = v }
  fun setIsResettingSession(v: Boolean) { _isResetting = v }
}

// ChatView composable that renders messages and input; callbacks mimic Gallery flow surface
@Composable
fun ChatView(
  viewModel: ChatViewModel,
  onSendMessage: (List<ChatMessage>) -> Unit,
  onRunAgainClicked: (ChatMessageText) -> Unit,
  onResetSessionClicked: () -> Unit,
  showStopButtonInInputWhenInProgress: Boolean,
  onStopButtonClicked: () -> Unit,
  modifier: Modifier = Modifier
) {
  val scope = rememberCoroutineScope()
  val listState = rememberLazyListState()
  var input by remember { mutableStateOf("") }

  Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
    Column(modifier = Modifier.fillMaxSize().padding(12.dp)) {
      // Messages list
      Box(modifier = Modifier.weight(1f)) {
        LazyColumn(
          state = listState,
          modifier = Modifier.fillMaxSize()
        ) {
          itemsIndexed(viewModel.messages) { _, msg ->
            when (msg) {
              is ChatMessageText -> {
                val isUser = msg.side == ChatSide.USER
                Row(
                  modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                  horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
                ) {
                  val bubbleColor = if (isUser)
                    MaterialTheme.customColors.userBubbleBgColor
                  else
                    MaterialTheme.customColors.agentBubbleBgColor
                  val textColor = if (isUser) Color.White else MaterialTheme.colorScheme.onSurface
                  Column(horizontalAlignment = if (isUser) Alignment.End else Alignment.Start) {
                    Box(
                      modifier = Modifier
                        .background(bubbleColor, shape = MaterialTheme.shapes.medium)
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                    ) {
                      Text(msg.content, color = textColor)
                    }
                    // Actions
                    Row {
                      IconButton(onClick = { onRunAgainClicked(msg) }) { Text("↻") }
                    }
                  }
                }
              }
              is ChatMessageLoading -> {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
                  Text("…", modifier = Modifier.padding(8.dp))
                }
              }
              is ChatMessageWarning -> {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
                  Text(msg.content, color = Color(0xFFB00020))
                }
              }
              is ChatMessageImage -> {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
                  Text("[image x${msg.bitmaps.size}]")
                }
              }
              is ChatMessageAudioClip -> {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
                  Text("[audio]")
                }
              }
            }
          }
          item { Spacer(Modifier.height(60.dp)) }
        }
      }

      // Input row
      Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
      ) {
        OutlinedTextField(
          value = input,
          onValueChange = { input = it },
          modifier = Modifier.weight(1f),
          placeholder = { Text("Send a message") },
          singleLine = true,
          enabled = !viewModel.inProgress
        )
        if (showStopButtonInInputWhenInProgress && viewModel.inProgress) {
          Button(onClick = onStopButtonClicked) { Text("Stop") }
        } else {
          Button(
            onClick = {
              val text = input.trim()
              if (text.isNotEmpty()) {
                val msgs = listOf<ChatMessage>(ChatMessageText(text, side = ChatSide.USER))
                onSendMessage(msgs)
                input = ""
                scope.launch {
                  // Scroll to bottom after sending
                  // Best effort; ignore errors if state not laid out yet
                  runCatching { listState.scrollToItem(Int.MAX_VALUE) }
                }
              }
            }
          ) { Text("Send") }
        }
        Button(onClick = onResetSessionClicked, enabled = !viewModel.inProgress) { Text("Reset") }
      }
    }
  }
}

// Access to custom colors from Theme
val MaterialTheme.customColors: apps.visnkmr.batu.ui.theme.CustomColors
  @Composable get() = apps.visnkmr.batu.ui.theme.LocalCustomColors.current
