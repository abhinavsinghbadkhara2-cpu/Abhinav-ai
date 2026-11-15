package com.example.ui.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.ai.AiChatService
import com.example.data.ai.GeminiChatService
import com.example.data.ai.RateLimitException
import com.example.data.model.ChatMessage
import com.example.data.model.ChatSession
import com.example.data.model.LanguageMode
import com.example.data.repository.ChatStore
import com.google.firebase.firestore.FirebaseFirestoreException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed interface ChatsUiState {
    data object Loading : ChatsUiState
    data class Success(val chats: List<ChatSession>) : ChatsUiState
    data class Error(val message: String) : ChatsUiState
}

data class ChatScreenUiState(
    val activeChatId: String? = null,
    val inputText: String = "",
    val languageMode: LanguageMode = LanguageMode.AUTO,
    val isSending: Boolean = false,
    val isWaitingFirstToken: Boolean = false,
    val streamingText: String? = null,
    val localTurns: List<ChatMessage> = emptyList(),
    val lastFailedPrompt: String? = null,
    val errorMessage: String? = null,
    val showClearChatDialog: Boolean = false,
    val remainingRequestsInWindow: Int = GeminiChatService.MAX_REQUESTS_PER_WINDOW
)

class ChatViewModel(
    private val repository: ChatStore,
    private val aiService: AiChatService,
    private val currentUserId: String
) : ViewModel() {

    private val _uiState = MutableStateFlow(ChatScreenUiState())
    val uiState: StateFlow<ChatScreenUiState> = _uiState.asStateFlow()

    private val _activeChatId = MutableStateFlow<String?>(null)
    private var generationJob: Job? = null

    val chatsState: StateFlow<ChatsUiState> = repository.observeChats(currentUserId)
        .map<List<ChatSession>, ChatsUiState> { chats ->
            ChatsUiState.Success(chats)
        }
        .catch { error ->
            val firestoreError = generateSequence(error) { it.cause }
                .filterIsInstance<FirebaseFirestoreException>()
                .firstOrNull()
            if (firestoreError == null && error is CancellationException) throw error
            val rootError = firestoreError ?: error
            Log.w(TAG, "Error observing chats", rootError)
            emit(ChatsUiState.Error(rootError.localizedMessage ?: "Failed to load chat history."))
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
            initialValue = ChatsUiState.Loading
        )

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val messages: StateFlow<List<ChatMessage>> = _activeChatId
        .flatMapLatest { chatId ->
            if (chatId.isNullOrBlank()) {
                flowOf(emptyList())
            } else {
                repository.observeMessages(currentUserId, chatId)
                    .catch { error ->
                        val firestoreError = generateSequence(error) { it.cause }
                            .filterIsInstance<FirebaseFirestoreException>()
                            .firstOrNull()
                        if (firestoreError == null && error is CancellationException) throw error
                        val rootError = firestoreError ?: error
                        Log.w(TAG, "Error observing messages", rootError)
                        _uiState.update {
                            it.copy(errorMessage = rootError.localizedMessage ?: "Failed to load messages.")
                        }
                        emit(emptyList())
                    }
            }
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
            initialValue = emptyList()
        )

    fun onInputChanged(text: String) {
        if (text.length <= GeminiChatService.MAX_PROMPT_CHARS) {
            _uiState.update { it.copy(inputText = text) }
        }
    }

    fun selectChat(chat: ChatSession) {
        if (_uiState.value.isSending) {
            generationJob?.cancel()
        }
        _activeChatId.value = chat.id
        _uiState.update {
            it.copy(
                activeChatId = chat.id,
                languageMode = LanguageMode.fromCode(chat.languageHint),
                isSending = false,
                isWaitingFirstToken = false,
                streamingText = null,
                localTurns = emptyList(),
                lastFailedPrompt = null,
                errorMessage = null
            )
        }
    }

    fun startNewChat() {
        if (_uiState.value.isSending) {
            generationJob?.cancel()
        }
        _activeChatId.value = null
        _uiState.update {
            it.copy(
                activeChatId = null,
                isSending = false,
                isWaitingFirstToken = false,
                streamingText = null,
                localTurns = emptyList(),
                lastFailedPrompt = null,
                errorMessage = null
            )
        }
    }

    fun setLanguageMode(mode: LanguageMode) {
        _uiState.update { it.copy(languageMode = mode) }
        val chatId = _activeChatId.value ?: return
        viewModelScope.launch {
            try {
                repository.updateLanguageHint(currentUserId, chatId, mode.code)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Failed to persist language hint", e)
            }
        }
    }

    fun retryLastMessage() {
        val failedPrompt = _uiState.value.lastFailedPrompt ?: return
        sendMessage(overridePrompt = failedPrompt, isRetry = true)
    }

    fun sendMessage(overridePrompt: String? = null, isRetry: Boolean = false) {
        val rawPrompt = (overridePrompt ?: _uiState.value.inputText).trim()
        if (rawPrompt.isEmpty() || _uiState.value.isSending) return

        val languageMode = _uiState.value.languageMode
        val persistedSnapshot = messages.value
        val combinedHistory = buildEffectiveHistory(persistedSnapshot, _uiState.value.localTurns)
        val existingChatId = _activeChatId.value
        val targetChatId = if (existingChatId.isNullOrBlank()) {
            repository.generateChatId(currentUserId)
        } else {
            existingChatId
        }
        val isNewChat = existingChatId.isNullOrBlank()

        if (isNewChat) {
            _activeChatId.value = targetChatId
        }

        val optimisticUserMsg = if (isRetry) {
            null
        } else {
            ChatMessage(
                id = "local_user_${System.nanoTime()}",
                userId = currentUserId,
                chatId = targetChatId,
                role = ChatMessage.ROLE_USER,
                text = rawPrompt
            )
        }

        val historyForModel = if (isRetry) {
            // Exclude the trailing user message if it's already in combinedHistory
            val last = combinedHistory.lastOrNull()
            if (last != null && last.isUser && last.text == rawPrompt) {
                combinedHistory.dropLast(1)
            } else {
                combinedHistory
            }
        } else {
            combinedHistory
        }

        generationJob = viewModelScope.launch {
            _uiState.update {
                it.copy(
                    activeChatId = targetChatId,
                    inputText = if (overridePrompt == null) "" else it.inputText,
                    isSending = true,
                    isWaitingFirstToken = true,
                    streamingText = "",
                    localTurns = if (optimisticUserMsg != null) it.localTurns + optimisticUserMsg else it.localTurns,
                    lastFailedPrompt = null,
                    errorMessage = null
                )
            }

            // Persist the user message concurrently so Firestore never blocks Gemini from responding.
            val userPersistDeferred = async {
                val alreadyPersisted = isRetry &&
                    persistedSnapshot.any { it.isUser && it.text == rawPrompt }
                if (alreadyPersisted) return@async true
                try {
                    val existingChats = (chatsState.value as? ChatsUiState.Success)?.chats.orEmpty()
                    val currentChat = existingChats.firstOrNull { it.id == targetChatId }
                    if (isNewChat || currentChat == null) {
                        repository.createChatWithFirstMessage(
                            userId = currentUserId,
                            title = deriveChatTitle(rawPrompt),
                            languageHint = languageMode.code,
                            messageText = rawPrompt,
                            chatId = targetChatId
                        )
                    } else {
                        val updatedTitle = if (currentChat.title == "New Chat" || combinedHistory.isEmpty()) {
                            deriveChatTitle(rawPrompt)
                        } else {
                            null
                        }
                        repository.appendMessage(
                            userId = currentUserId,
                            chatId = targetChatId,
                            role = ChatMessage.ROLE_USER,
                            text = rawPrompt,
                            newMessageCount = historyForModel.size.toLong() + 1L,
                            updatedTitle = updatedTitle,
                            languageHint = languageMode.code
                        )
                    }
                    true
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "Non-fatal Firestore user message persist error", e)
                    false
                }
            }

            try {
                val responseBuilder = StringBuilder()
                aiService.streamReply(
                    history = historyForModel,
                    userPrompt = rawPrompt,
                    languageMode = languageMode
                ).collect { accumulated ->
                    responseBuilder.clear()
                    responseBuilder.append(accumulated)
                    _uiState.update {
                        it.copy(
                            isWaitingFirstToken = false,
                            streamingText = accumulated
                        )
                    }
                }

                val finalReply = responseBuilder.toString().trim()
                if (finalReply.isEmpty()) {
                    throw IllegalStateException("Abhinav AI returned an empty response. Please try again.")
                }

                val optimisticModelMsg = ChatMessage(
                    id = "local_model_${System.nanoTime()}",
                    userId = currentUserId,
                    chatId = targetChatId,
                    role = ChatMessage.ROLE_MODEL,
                    text = finalReply
                )

                // Keep reply visible immediately in localTurns while persisting to Firestore.
                _uiState.update {
                    it.copy(
                        isSending = false,
                        isWaitingFirstToken = false,
                        streamingText = null,
                        localTurns = it.localTurns + optimisticModelMsg,
                        remainingRequestsInWindow = aiService.remainingRequestsInWindow()
                    )
                }

                val userSaved = userPersistDeferred.await()
                if (userSaved) {
                    try {
                        repository.appendMessage(
                            userId = currentUserId,
                            chatId = targetChatId,
                            role = ChatMessage.ROLE_MODEL,
                            text = finalReply,
                            newMessageCount = historyForModel.size.toLong() + 2L
                        )
                        // Clear local turns once Firestore catches up or keep deduplicated in UI
                        _uiState.update { state ->
                            state.copy(
                                localTurns = state.localTurns.filterNot {
                                    (optimisticUserMsg != null && it.id == optimisticUserMsg.id) ||
                                        it.id == optimisticModelMsg.id
                                }
                            )
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.w(TAG, "Non-fatal Firestore model message persist error", e)
                    }
                }
            } catch (e: CancellationException) {
                _uiState.update {
                    it.copy(
                        isSending = false,
                        isWaitingFirstToken = false,
                        streamingText = null
                    )
                }
                throw e
            } catch (e: RateLimitException) {
                Log.w(TAG, "Rate limit hit: ${e.message}")
                _uiState.update {
                    it.copy(
                        inputText = if (overridePrompt == null && it.inputText.isEmpty()) rawPrompt else it.inputText,
                        isSending = false,
                        isWaitingFirstToken = false,
                        streamingText = null,
                        localTurns = if (optimisticUserMsg != null) {
                            it.localTurns.filterNot { msg -> msg.id == optimisticUserMsg.id }
                        } else {
                            it.localTurns
                        },
                        errorMessage = e.message,
                        remainingRequestsInWindow = aiService.remainingRequestsInWindow()
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error during chat turn", e)
                _uiState.update {
                    it.copy(
                        isSending = false,
                        isWaitingFirstToken = false,
                        streamingText = null,
                        lastFailedPrompt = rawPrompt,
                        errorMessage = mapFriendlyAiError(e),
                        remainingRequestsInWindow = aiService.remainingRequestsInWindow()
                    )
                }
            }
        }
    }

    fun stopGeneration() {
        generationJob?.cancel()
        generationJob = null
        _uiState.update {
            it.copy(
                isSending = false,
                isWaitingFirstToken = false,
                streamingText = null
            )
        }
    }

    fun requestClearChat() {
        _uiState.update { it.copy(showClearChatDialog = true) }
    }

    fun dismissClearChatDialog() {
        _uiState.update { it.copy(showClearChatDialog = false) }
    }

    fun confirmClearChat() {
        val chatId = _activeChatId.value
        _uiState.update { it.copy(showClearChatDialog = false) }
        if (chatId.isNullOrBlank()) {
            _uiState.update {
                it.copy(
                    localTurns = emptyList(),
                    lastFailedPrompt = null,
                    errorMessage = null
                )
            }
            return
        }

        if (_uiState.value.isSending) {
            generationJob?.cancel()
        }

        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isSending = false,
                    isWaitingFirstToken = false,
                    streamingText = null,
                    localTurns = emptyList(),
                    lastFailedPrompt = null,
                    errorMessage = null
                )
            }
            try {
                repository.clearChatMessages(currentUserId, chatId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Failed to clear chat", e)
                _uiState.update {
                    it.copy(errorMessage = e.localizedMessage ?: "Failed to clear chat messages.")
                }
            }
        }
    }

    fun deleteChat(chatId: String) {
        viewModelScope.launch {
            try {
                if (_activeChatId.value == chatId) {
                    if (_uiState.value.isSending) generationJob?.cancel()
                    _activeChatId.value = null
                    _uiState.update {
                        it.copy(
                            activeChatId = null,
                            isSending = false,
                            isWaitingFirstToken = false,
                            streamingText = null,
                            localTurns = emptyList(),
                            lastFailedPrompt = null
                        )
                    }
                }
                repository.deleteChat(currentUserId, chatId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Failed to delete chat", e)
                _uiState.update {
                    it.copy(errorMessage = e.localizedMessage ?: "Failed to delete chat.")
                }
            }
        }
    }

    fun dismissError() {
        _uiState.update { it.copy(errorMessage = null, lastFailedPrompt = null) }
    }

    private fun buildEffectiveHistory(
        persisted: List<ChatMessage>,
        local: List<ChatMessage>
    ): List<ChatMessage> {
        if (local.isEmpty()) return persisted
        val extra = local.filter { localMsg ->
            persisted.none { it.role == localMsg.role && it.text == localMsg.text }
        }
        return persisted + extra
    }

    private fun deriveChatTitle(prompt: String): String {
        val singleLine = prompt.replace("\n", " ").trim()
        return if (singleLine.length <= 42) singleLine else singleLine.take(40).trimEnd() + "…"
    }

    private fun mapFriendlyAiError(error: Exception): String {
        return error.localizedMessage ?: error.message ?: "Server error: Unable to reach Abhinav AI."
    }

    private companion object {
        const val TAG = "ChatViewModel"
        const val STOP_TIMEOUT_MILLIS = 5000L
    }
}
