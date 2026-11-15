package com.example.ui.viewmodel

import com.example.data.ai.AiChatService
import com.example.data.ai.GeminiServiceException
import com.example.data.model.ChatMessage
import com.example.data.model.ChatSession
import com.example.data.model.LanguageMode
import com.example.data.repository.ChatStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ChatViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private class InMemoryChatStore(
        var failWrites: Boolean = false
    ) : ChatStore {
        private var nextChatNum = 1
        private var nextMsgNum = 1
        private val chatsFlow = MutableStateFlow<List<ChatSession>>(emptyList())
        private val messagesByChat = MutableStateFlow<Map<String, List<ChatMessage>>>(emptyMap())

        override fun generateChatId(userId: String): String = "chat_${nextChatNum++}"

        override fun observeChats(userId: String): Flow<List<ChatSession>> = chatsFlow

        override fun observeMessages(userId: String, chatId: String): Flow<List<ChatMessage>> =
            messagesByChat.map { it[chatId].orEmpty() }

        override suspend fun createChatWithFirstMessage(
            userId: String,
            title: String,
            languageHint: String,
            messageText: String,
            chatId: String
        ): String {
            if (failWrites) throw IllegalStateException("Simulated Firestore write failure")
            val id = chatId.ifBlank { generateChatId(userId) }
            val session = ChatSession(
                id = id,
                userId = userId,
                title = title,
                languageHint = languageHint,
                lastMessagePreview = messageText,
                messageCount = 1L
            )
            chatsFlow.value = listOf(session) + chatsFlow.value.filterNot { it.id == id }
            val msg = ChatMessage(
                id = "msg_${nextMsgNum++}",
                userId = userId,
                chatId = id,
                role = ChatMessage.ROLE_USER,
                text = messageText
            )
            messagesByChat.value = messagesByChat.value + (id to listOf(msg))
            return id
        }

        override suspend fun appendMessage(
            userId: String,
            chatId: String,
            role: String,
            text: String,
            newMessageCount: Long,
            updatedTitle: String?,
            languageHint: String?
        ) {
            if (failWrites) throw IllegalStateException("Simulated Firestore append failure")
            val msg = ChatMessage(
                id = "msg_${nextMsgNum++}",
                userId = userId,
                chatId = chatId,
                role = role,
                text = text
            )
            val existing = messagesByChat.value[chatId].orEmpty()
            messagesByChat.value = messagesByChat.value + (chatId to (existing + msg))
            chatsFlow.value = chatsFlow.value.map { chat ->
                if (chat.id == chatId) {
                    chat.copy(
                        title = updatedTitle ?: chat.title,
                        languageHint = languageHint ?: chat.languageHint,
                        lastMessagePreview = text,
                        messageCount = newMessageCount
                    )
                } else {
                    chat
                }
            }
        }

        override suspend fun updateLanguageHint(userId: String, chatId: String, languageHint: String) = Unit

        override suspend fun clearChatMessages(userId: String, chatId: String) {
            messagesByChat.value = messagesByChat.value + (chatId to emptyList())
        }

        override suspend fun deleteChat(userId: String, chatId: String) {
            chatsFlow.value = chatsFlow.value.filterNot { it.id == chatId }
            messagesByChat.value = messagesByChat.value - chatId
        }

        fun currentMessages(chatId: String): List<ChatMessage> = messagesByChat.value[chatId].orEmpty()
    }

    private class FakeAiChatService(
        var nextBehavior: suspend (List<ChatMessage>, String, LanguageMode) -> List<String> =
            { _, prompt, _ -> listOf("नमस्ते! ", "Response to: $prompt") },
        var nextException: Exception? = null
    ) : AiChatService {
        var lastReceivedHistory: List<ChatMessage> = emptyList()
        var callCount = 0

        override fun remainingRequestsInWindow(nowMs: Long): Int = 29

        override fun streamReply(
            history: List<ChatMessage>,
            userPrompt: String,
            languageMode: LanguageMode
        ): Flow<String> = flow {
            callCount++
            lastReceivedHistory = history
            nextException?.let { throw it }
            val chunks = nextBehavior(history, userPrompt, languageMode)
            val sb = StringBuilder()
            for (chunk in chunks) {
                delay(10)
                sb.append(chunk)
                emit(sb.toString())
            }
        }
    }

    @Test
    fun sendMessage_streamsResponseAndPersistsChatTurn() = runTest {
        val store = InMemoryChatStore()
        val ai = FakeAiChatService()
        val vm = ChatViewModel(store, ai, "user_1")

        vm.onInputChanged("Explain Kotlin coroutines")
        vm.sendMessage()

        advanceUntilIdle()

        val state = vm.uiState.value
        assertEquals("", state.inputText)
        assertFalse(state.isSending)
        assertFalse(state.isWaitingFirstToken)
        assertNull(state.errorMessage)
        val chatId = state.activeChatId!!
        val saved = store.currentMessages(chatId)
        assertEquals(2, saved.size)
        assertEquals(ChatMessage.ROLE_USER, saved[0].role)
        assertEquals("Explain Kotlin coroutines", saved[0].text)
        assertEquals(ChatMessage.ROLE_MODEL, saved[1].role)
        assertEquals("नमस्ते! Response to: Explain Kotlin coroutines", saved[1].text)
    }

    @Test
    fun sendMessage_stillShowsAiReplyWhenFirestoreWriteFails() = runTest {
        val store = InMemoryChatStore(failWrites = true)
        val ai = FakeAiChatService()
        val vm = ChatViewModel(store, ai, "user_1")

        vm.onInputChanged("Hello")
        vm.sendMessage()
        advanceUntilIdle()

        val state = vm.uiState.value
        assertNull(state.errorMessage)
        assertEquals(2, state.localTurns.size)
        assertEquals("Hello", state.localTurns[0].text)
        assertEquals("नमस्ते! Response to: Hello", state.localTurns[1].text)
    }

    @Test
    fun sendMessage_surfacesRealErrorAndRetriesWithoutDuplicatingUserMessage() = runTest {
        val store = InMemoryChatStore()
        val ai = FakeAiChatService(
            nextException = GeminiServiceException(
                category = "Invalid API key",
                detail = "Firebase App Check or API authorization rejected (403)"
            )
        )
        val vm = ChatViewModel(store, ai, "user_1")

        vm.onInputChanged("Test prompt")
        vm.sendMessage()
        advanceUntilIdle()

        val failedState = vm.uiState.value
        assertEquals(
            "Invalid API key: Firebase App Check or API authorization rejected (403)",
            failedState.errorMessage
        )
        assertEquals("Test prompt", failedState.lastFailedPrompt)
        assertEquals(1, failedState.localTurns.size)

        // Recover AI service and retry
        ai.nextException = null
        vm.retryLastMessage()
        advanceUntilIdle()

        val recoveredState = vm.uiState.value
        assertNull(recoveredState.errorMessage)
        assertNull(recoveredState.lastFailedPrompt)
        assertTrue(ai.lastReceivedHistory.isEmpty())

        val chatId = recoveredState.activeChatId!!
        val saved = store.currentMessages(chatId)
        assertEquals(2, saved.size)
        assertEquals("Test prompt", saved[0].text)
        assertEquals("नमस्ते! Response to: Test prompt", saved[1].text)
    }
}
