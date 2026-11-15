package com.example.data.ai

import android.util.Log
import com.example.data.model.ChatMessage
import com.example.data.model.LanguageMode
import com.google.firebase.Firebase
import com.google.firebase.FirebaseApp
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.InvalidAPIKeyException
import com.google.firebase.ai.type.PromptBlockedException
import com.google.firebase.ai.type.QuotaExceededException
import com.google.firebase.ai.type.RequestOptions
import com.google.firebase.ai.type.RequestTimeoutException
import com.google.firebase.ai.type.ResponseStoppedException
import com.google.firebase.ai.type.ServerException
import com.google.firebase.ai.type.ServiceDisabledException
import com.google.firebase.ai.type.content
import com.google.firebase.ai.type.generationConfig
import com.google.firebase.appcheck.FirebaseAppCheck
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeout

sealed class RateLimitException(message: String) : Exception(message)
class CooldownLimitException(remainingMs: Long) :
    RateLimitException("Please wait ${((remainingMs + 999) / 1000)}s before sending another message.")

class WindowQuotaExceededException(maxRequests: Int) :
    RateLimitException("Request limit reached ($maxRequests messages per 10 minutes). Please wait a moment before trying again.")

class PromptTooLongException(maxChars: Int) :
    RateLimitException("Message is too long (max $maxChars characters).")

class GeminiServiceException(
    val category: String,
    val detail: String,
    cause: Throwable? = null
) : Exception(if (detail.isNotBlank()) "$category: $detail" else category, cause)

interface AiChatService {
    fun remainingRequestsInWindow(nowMs: Long = System.currentTimeMillis()): Int
    fun streamReply(
        history: List<ChatMessage>,
        userPrompt: String,
        languageMode: LanguageMode
    ): Flow<String>
}

class GeminiChatService internal constructor(
    private val appCheckTimeoutMs: Long = APP_CHECK_TIMEOUT_MS,
    private val firstTokenTimeoutMs: Long = FIRST_TOKEN_TIMEOUT_MS,
    private val streamTotalTimeoutMs: Long = STREAM_TOTAL_TIMEOUT_MS,
    private val unaryFallbackTimeoutMs: Long = UNARY_FALLBACK_TIMEOUT_MS,
    private val apiKeyLookup: (() -> String)? = null,
    private val appCheckVerifier: (suspend () -> Unit)? = null,
    private val customStreamRunner: ((List<ChatMessage>, String) -> Flow<String>)? = null,
    private val customUnaryRunner: (suspend (List<ChatMessage>, String) -> String)? = null
) : AiChatService {

    constructor() : this(
        appCheckTimeoutMs = APP_CHECK_TIMEOUT_MS,
        firstTokenTimeoutMs = FIRST_TOKEN_TIMEOUT_MS,
        streamTotalTimeoutMs = STREAM_TOTAL_TIMEOUT_MS,
        unaryFallbackTimeoutMs = UNARY_FALLBACK_TIMEOUT_MS
    )

    private val model by lazy {
        Firebase.ai.generativeModel(
            modelName = MODEL_NAME,
            generationConfig = generationConfig {
                temperature = 0.7f
                topP = 0.95f
                maxOutputTokens = 2048
            },
            systemInstruction = content {
                text(
                    """
                    You are ABHINAV AI, a helpful, intelligent, and friendly bilingual AI assistant.
                    - Fluently understand and respond in both English and Hindi (Devanagari script like हिंदी and conversational Hinglish).
                    - By default, reply in the same language and script the user used unless a specific language preference is requested.
                    - Keep formatting clean, well-structured, and easy to read on mobile screens (use short paragraphs, clear bullet points, and concise code blocks when helpful).
                    - Be accurate, honest, and helpful. Never fabricate citations or pretend to have executed tools you did not run.
                    """.trimIndent()
                )
            },
            requestOptions = RequestOptions(45_000L)
        )
    }

    private val recentRequestTimestamps = ArrayDeque<Long>()
    private var lastRequestTimestamp = 0L

    @Synchronized
    fun checkAndRecordRequest(prompt: String, nowMs: Long = System.currentTimeMillis()) {
        if (prompt.length > MAX_PROMPT_CHARS) {
            throw PromptTooLongException(MAX_PROMPT_CHARS)
        }
        val elapsedSinceLast = nowMs - lastRequestTimestamp
        if (lastRequestTimestamp > 0L && elapsedSinceLast < COOLDOWN_MS) {
            throw CooldownLimitException(COOLDOWN_MS - elapsedSinceLast)
        }
        while (recentRequestTimestamps.isNotEmpty() && nowMs - recentRequestTimestamps.first() > WINDOW_MS) {
            recentRequestTimestamps.removeFirst()
        }
        if (recentRequestTimestamps.size >= MAX_REQUESTS_PER_WINDOW) {
            throw WindowQuotaExceededException(MAX_REQUESTS_PER_WINDOW)
        }
        recentRequestTimestamps.addLast(nowMs)
        lastRequestTimestamp = nowMs
    }

    @Synchronized
    override fun remainingRequestsInWindow(nowMs: Long): Int {
        while (recentRequestTimestamps.isNotEmpty() && nowMs - recentRequestTimestamps.first() > WINDOW_MS) {
            recentRequestTimestamps.removeFirst()
        }
        return (MAX_REQUESTS_PER_WINDOW - recentRequestTimestamps.size).coerceAtLeast(0)
    }

    override fun streamReply(
        history: List<ChatMessage>,
        userPrompt: String,
        languageMode: LanguageMode
    ): Flow<String> = channelFlow {
        checkAndRecordRequest(userPrompt)

        val apiKey = try {
            apiKeyLookup?.invoke() ?: FirebaseApp.getInstance().options.apiKey
        } catch (e: Exception) {
            throw GeminiServiceException(
                category = "Missing API key",
                detail = "Firebase configuration is not initialized (${e.localizedMessage ?: "missing FirebaseApp"}).",
                cause = e
            )
        }
        if (apiKey.isBlank()) {
            throw GeminiServiceException(
                category = "Missing API key",
                detail = "Firebase API key is missing in project configuration."
            )
        }

        // Verify App Check token upfront with a bounded timeout so DebugAppCheckProvider's
        // default HttpURLConnection (infinite timeout) can never hang the request silently.
        try {
            withTimeout(appCheckTimeoutMs) {
                if (appCheckVerifier != null) {
                    appCheckVerifier.invoke()
                } else {
                    FirebaseAppCheck.getInstance().getAppCheckToken(false).await()
                }
            }
        } catch (e: TimeoutCancellationException) {
            Log.e(TAG, "Firebase App Check token fetch timed out after ${appCheckTimeoutMs}ms", e)
            throw GeminiServiceException(
                category = "Network request failed",
                detail = "Firebase App Check token request timed out. Please check your connection and retry.",
                cause = e
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Firebase App Check token fetch failed: ${e.message}", e)
            throw classifyAiError(e)
        }

        val effectivePrompt = when (languageMode) {
            LanguageMode.AUTO -> userPrompt
            LanguageMode.ENGLISH -> "[Please reply in clear English]\n$userPrompt"
            LanguageMode.HINDI -> "[कृपया सरल और स्पष्ट हिंदी (देवनागरी) में उत्तर दें]\n$userPrompt"
        }

        val sanitizedHistory = sanitizeHistory(history)
        val chatHistory = if (customStreamRunner == null || customUnaryRunner == null) {
            sanitizedHistory.map { msg ->
                val role = if (msg.isUser) "user" else "model"
                content(role) { text(msg.text) }
            }
        } else {
            emptyList()
        }

        val accumulated = StringBuilder()
        var receivedFirstChunk = false
        var streamStalledBeforeFirstToken = false
        var streamTimedOut = false

        try {
            coroutineScope {
                val streamJob = launch {
                    try {
                        withTimeout(streamTotalTimeoutMs) {
                            val tokenFlow = if (customStreamRunner != null) {
                                customStreamRunner.invoke(sanitizedHistory, effectivePrompt)
                            } else {
                                val chat = model.startChat(history = chatHistory)
                                kotlinx.coroutines.flow.flow {
                                    chat.sendMessageStream(effectivePrompt).collect { chunk ->
                                        val delta = chunk.text.orEmpty()
                                        if (delta.isNotEmpty()) emit(delta)
                                    }
                                }
                            }
                            tokenFlow.collect { delta ->
                                if (delta.isNotEmpty()) {
                                    receivedFirstChunk = true
                                    accumulated.append(delta)
                                    send(accumulated.toString())
                                }
                            }
                        }
                    } catch (e: TimeoutCancellationException) {
                        if (!streamStalledBeforeFirstToken) {
                            streamTimedOut = true
                        }
                    }
                }

                val firstTokenWatchdog = launch {
                    delay(firstTokenTimeoutMs)
                    if (!receivedFirstChunk) {
                        streamStalledBeforeFirstToken = true
                        streamJob.cancel(CancellationException("Stream stalled waiting for first token"))
                    }
                }

                try {
                    streamJob.join()
                } finally {
                    firstTokenWatchdog.cancel()
                }
            }

            if (streamTimedOut) {
                throw GeminiServiceException(
                    category = "Network request failed",
                    detail = "Request timed out after ${streamTotalTimeoutMs / 1000} seconds. Please check your connection and retry."
                )
            }

            if (streamStalledBeforeFirstToken || accumulated.isBlank()) {
                if (streamStalledBeforeFirstToken) {
                    Log.w(
                        TAG,
                        "Gemini stream produced no token within ${firstTokenTimeoutMs}ms; falling back to unary generateContent."
                    )
                }
                val fallbackReply = try {
                    withTimeout(unaryFallbackTimeoutMs) {
                        if (customUnaryRunner != null) {
                            customUnaryRunner.invoke(sanitizedHistory, effectivePrompt)
                        } else {
                            val fallbackChat = model.startChat(history = chatHistory)
                            fallbackChat.sendMessage(effectivePrompt).text.orEmpty()
                        }
                    }
                } catch (e: TimeoutCancellationException) {
                    Log.e(TAG, "Gemini unary fallback timed out after ${unaryFallbackTimeoutMs}ms", e)
                    throw GeminiServiceException(
                        category = "Network request failed",
                        detail = "Gemini request timed out. Please check your internet connection and retry.",
                        cause = e
                    )
                }
                if (fallbackReply.isBlank()) {
                    throw GeminiServiceException(
                        category = "Server error",
                        detail = "Gemini returned an empty response. Please try again."
                    )
                }
                send(fallbackReply)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: GeminiServiceException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Gemini streaming failed: ${e::class.java.simpleName}: ${e.message}", e)
            throw classifyAiError(e)
        }
    }.flowOn(Dispatchers.IO)

    internal fun sanitizeHistory(history: List<ChatMessage>): List<ChatMessage> {
        val nonBlank = history
            .takeLast(MAX_CONTEXT_TURNS)
            .filter { it.text.isNotBlank() }
            .dropWhile { !it.isUser }

        if (nonBlank.isEmpty()) return emptyList()

        // Ensure strictly alternating user/model turns and end with a model turn before the new user prompt.
        val alternating = mutableListOf<ChatMessage>()
        for (msg in nonBlank) {
            val prev = alternating.lastOrNull()
            if (prev != null && prev.isUser == msg.isUser) {
                alternating[alternating.lastIndex] = prev.copy(text = "${prev.text}\n${msg.text}")
            } else {
                alternating.add(msg)
            }
        }
        if (alternating.lastOrNull()?.isUser == true) {
            alternating.removeAt(alternating.lastIndex)
        }
        return alternating
    }

    internal fun classifyAiError(error: Throwable): GeminiServiceException {
        val chain = generateSequence(error) { it.cause }.toList()
        val rawMessage = chain
            .mapNotNull { it.localizedMessage ?: it.message }
            .firstOrNull { it.isNotBlank() }
            ?: error::class.java.simpleName

        val networkCause = chain.firstOrNull {
            it is UnknownHostException ||
                it is ConnectException ||
                it is SocketTimeoutException ||
                it is SSLException ||
                it is IOException
        }

        val invalidKeyCause = chain.firstOrNull { it is InvalidAPIKeyException }
        val quotaCause = chain.firstOrNull { it is QuotaExceededException }
        val serviceDisabledCause = chain.firstOrNull { it is ServiceDisabledException }
        val timeoutCause = chain.firstOrNull { it is RequestTimeoutException }
        val serverCause = chain.firstOrNull { it is ServerException }
        val blockedCause = chain.firstOrNull { it is PromptBlockedException || it is ResponseStoppedException }

        return when {
            rawMessage.contains("API key not found", ignoreCase = true) ||
                rawMessage.contains("missing api key", ignoreCase = true) ->
                GeminiServiceException("Missing API key", rawMessage, error)

            invalidKeyCause != null ||
                rawMessage.contains("API key not valid", ignoreCase = true) ||
                rawMessage.contains("API_KEY_INVALID", ignoreCase = true) ||
                rawMessage.contains("UNAUTHENTICATED", ignoreCase = true) ||
                rawMessage.contains("401") ->
                GeminiServiceException("Invalid API key", rawMessage, error)

            rawMessage.contains("403") ||
                rawMessage.contains("AppCheck", ignoreCase = true) ||
                rawMessage.contains("App Check", ignoreCase = true) ||
                rawMessage.contains("attestation", ignoreCase = true) ->
                GeminiServiceException(
                    "Invalid API key",
                    "Firebase App Check or API authorization rejected (403): $rawMessage",
                    error
                )

            quotaCause != null ||
                rawMessage.contains("429") ||
                rawMessage.contains("quota", ignoreCase = true) ||
                rawMessage.contains("RESOURCE_EXHAUSTED", ignoreCase = true) ->
                GeminiServiceException("API quota exceeded", rawMessage, error)

            serviceDisabledCause != null ||
                rawMessage.contains("404") ||
                rawMessage.contains("is not found", ignoreCase = true) ||
                rawMessage.contains("not supported", ignoreCase = true) ||
                rawMessage.contains("unsupported model", ignoreCase = true) ->
                GeminiServiceException(
                    "Unsupported model",
                    "Model $MODEL_NAME or Firebase AI Logic service is unavailable: $rawMessage",
                    error
                )

            blockedCause != null ->
                GeminiServiceException("Server error", "Response blocked by safety policy: $rawMessage", error)

            timeoutCause != null ->
                GeminiServiceException("Network request failed", "Request timed out: $rawMessage", error)

            serverCause != null ||
                rawMessage.contains("500") ||
                rawMessage.contains("502") ||
                rawMessage.contains("503") ||
                rawMessage.contains("INTERNAL", ignoreCase = true) ->
                GeminiServiceException("Server error", rawMessage, error)

            networkCause != null ||
                rawMessage.contains("Unable to resolve host", ignoreCase = true) ||
                rawMessage.contains("ConnectException", ignoreCase = true) ||
                rawMessage.contains("SocketTimeoutException", ignoreCase = true) ||
                rawMessage.contains("Failed to connect", ignoreCase = true) ->
                GeminiServiceException(
                    "Network request failed",
                    "${networkCause?.javaClass?.simpleName ?: "Connection error"}: $rawMessage",
                    error
                )

            else ->
                GeminiServiceException("Server error", rawMessage, error)
        }
    }

    companion object {
        private const val TAG = "GeminiChatService"
        const val MODEL_NAME = "gemini-3.8-flash"
        private const val APP_CHECK_TIMEOUT_MS = 15_000L
        private const val FIRST_TOKEN_TIMEOUT_MS = 25_000L
        private const val STREAM_TOTAL_TIMEOUT_MS = 60_000L
        private const val UNARY_FALLBACK_TIMEOUT_MS = 25_000L
        const val MAX_PROMPT_CHARS = 4000
        const val COOLDOWN_MS = 1500L
        const val WINDOW_MS = 10 * 60 * 1000L
        const val MAX_REQUESTS_PER_WINDOW = 30
        private const val MAX_CONTEXT_TURNS = 24
    }
}
