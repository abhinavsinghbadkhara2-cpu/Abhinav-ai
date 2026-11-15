package com.example.data.ai

import com.example.data.model.ChatMessage
import com.example.data.model.LanguageMode
import java.net.UnknownHostException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class GeminiChatServiceTest {

    @Test
    fun checkAndRecordRequest_enforcesCooldownLimit() {
        val service = GeminiChatService()
        service.checkAndRecordRequest("Hello", nowMs = 10_000L)

        assertThrows(CooldownLimitException::class.java) {
            service.checkAndRecordRequest("Too fast", nowMs = 10_500L)
        }

        service.checkAndRecordRequest("After cooldown", nowMs = 12_000L)
        assertEquals(GeminiChatService.MAX_REQUESTS_PER_WINDOW - 2, service.remainingRequestsInWindow(12_000L))
    }

    @Test
    fun checkAndRecordRequest_enforcesPromptLengthLimit() {
        val service = GeminiChatService()
        val oversized = "a".repeat(GeminiChatService.MAX_PROMPT_CHARS + 1)

        assertThrows(PromptTooLongException::class.java) {
            service.checkAndRecordRequest(oversized, nowMs = 1_000L)
        }
    }

    @Test
    fun checkAndRecordRequest_enforcesRollingWindowLimit() {
        val service = GeminiChatService()
        var now = 100_000L
        repeat(GeminiChatService.MAX_REQUESTS_PER_WINDOW) {
            service.checkAndRecordRequest("Message $it", nowMs = now)
            now += GeminiChatService.COOLDOWN_MS + 100L
        }

        assertEquals(0, service.remainingRequestsInWindow(now))
        assertThrows(WindowQuotaExceededException::class.java) {
            service.checkAndRecordRequest("Over quota", nowMs = now)
        }

        val afterWindow = 100_000L + GeminiChatService.WINDOW_MS + 5_000L
        service.checkAndRecordRequest("Reset window", nowMs = afterWindow)
    }

    @Test
    fun sanitizeHistory_enforcesAlternatingRolesAndTrailingModelTurn() {
        val service = GeminiChatService()
        val raw = listOf(
            ChatMessage(id = "0", role = ChatMessage.ROLE_MODEL, text = "Orphan model"),
            ChatMessage(id = "1", role = ChatMessage.ROLE_USER, text = "Hi"),
            ChatMessage(id = "2", role = ChatMessage.ROLE_USER, text = "How are you?"),
            ChatMessage(id = "3", role = ChatMessage.ROLE_MODEL, text = "I am great!"),
            ChatMessage(id = "4", role = ChatMessage.ROLE_USER, text = "Failed turn")
        )

        val sanitized = service.sanitizeHistory(raw)
        assertEquals(2, sanitized.size)
        assertEquals(ChatMessage.ROLE_USER, sanitized[0].role)
        assertEquals("Hi\nHow are you?", sanitized[0].text)
        assertEquals(ChatMessage.ROLE_MODEL, sanitized[1].role)
        assertEquals("I am great!", sanitized[1].text)
    }

    @Test
    fun classifyAiError_preservesSpecificCausesWithoutHidingBehindNetworkError() {
        val service = GeminiChatService()

        val missingKey = service.classifyAiError(IllegalStateException("Missing API key in config"))
        assertEquals("Missing API key", missingKey.category)

        val invalidKey = service.classifyAiError(IllegalStateException("API_KEY_INVALID on network call"))
        assertEquals("Invalid API key", invalidKey.category)

        val quota = service.classifyAiError(IllegalStateException("HTTP 429 RESOURCE_EXHAUSTED quota exceeded"))
        assertEquals("API quota exceeded", quota.category)

        val unsupportedModel = service.classifyAiError(IllegalStateException("404 model is not found"))
        assertEquals("Unsupported model", unsupportedModel.category)

        val server = service.classifyAiError(IllegalStateException("HTTP 500 internal server failure"))
        assertEquals("Server error", server.category)

        val network = service.classifyAiError(UnknownHostException("firebasevertexai.googleapis.com"))
        assertEquals("Network request failed", network.category)
        assertTrue(network.message?.contains("UnknownHostException") == true)
    }

    @Test
    fun streamReply_fallsBackToUnaryWhenStreamStallsBeforeFirstToken() = runTest {
        var unaryCalled = false
        val service = GeminiChatService(
            appCheckTimeoutMs = 500L,
            firstTokenTimeoutMs = 150L,
            streamTotalTimeoutMs = 1_000L,
            unaryFallbackTimeoutMs = 500L,
            apiKeyLookup = { "test-api-key" },
            appCheckVerifier = {},
            customStreamRunner = { _, _ ->
                flow {
                    delay(2_000L) // Stalls before emitting any token
                    emit("Too late")
                }
            },
            customUnaryRunner = { _, prompt ->
                unaryCalled = true
                "Unary reply for: $prompt"
            }
        )

        val emissions = service.streamReply(emptyList(), "Hello", LanguageMode.AUTO).toList()
        assertTrue(unaryCalled)
        assertEquals(listOf("Unary reply for: Hello"), emissions)
    }

    @Test
    fun streamReply_streamsChunksDirectlyWithoutUnaryFallbackWhenHealthy() = runTest {
        var unaryCalled = false
        val service = GeminiChatService(
            appCheckTimeoutMs = 500L,
            firstTokenTimeoutMs = 500L,
            streamTotalTimeoutMs = 1_000L,
            unaryFallbackTimeoutMs = 500L,
            apiKeyLookup = { "test-api-key" },
            appCheckVerifier = {},
            customStreamRunner = { _, _ ->
                flow {
                    emit("Hello ")
                    delay(20L)
                    emit("world!")
                }
            },
            customUnaryRunner = { _, _ ->
                unaryCalled = true
                "Should not be used"
            }
        )

        val emissions = service.streamReply(emptyList(), "Hi", LanguageMode.AUTO).toList()
        assertFalse(unaryCalled)
        assertEquals(listOf("Hello ", "Hello world!"), emissions)
    }

    @Test
    fun streamReply_failsFastWithClearMessageWhenAppCheckHangs() = runTest {
        val service = GeminiChatService(
            appCheckTimeoutMs = 100L,
            firstTokenTimeoutMs = 500L,
            streamTotalTimeoutMs = 1_000L,
            unaryFallbackTimeoutMs = 500L,
            apiKeyLookup = { "test-api-key" },
            appCheckVerifier = { delay(2_000L) },
            customStreamRunner = { _, _ -> flow { emit("Never reached") } },
            customUnaryRunner = { _, _ -> "Never reached" }
        )

        var caught: GeminiServiceException? = null
        try {
            service.streamReply(emptyList(), "Test", LanguageMode.AUTO).toList()
        } catch (e: GeminiServiceException) {
            caught = e
        }

        assertEquals("Network request failed", caught?.category)
        assertTrue(caught?.message?.contains("App Check", ignoreCase = true) == true)
    }
}
