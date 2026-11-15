package com.example.data.repository

import android.content.Context
import com.example.R
import com.example.data.firebase.OperationType
import com.example.data.firebase.handleFirestoreError
import com.example.data.model.ChatMessage
import com.example.data.model.ChatSession
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.snapshots
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeout

interface ChatStore {
    fun generateChatId(userId: String): String
    fun observeChats(userId: String): Flow<List<ChatSession>>
    fun observeMessages(userId: String, chatId: String): Flow<List<ChatMessage>>
    suspend fun createChatWithFirstMessage(
        userId: String,
        title: String,
        languageHint: String,
        messageText: String,
        chatId: String = ""
    ): String
    suspend fun appendMessage(
        userId: String,
        chatId: String,
        role: String,
        text: String,
        newMessageCount: Long,
        updatedTitle: String? = null,
        languageHint: String? = null
    )
    suspend fun updateLanguageHint(userId: String, chatId: String, languageHint: String)
    suspend fun clearChatMessages(userId: String, chatId: String)
    suspend fun deleteChat(userId: String, chatId: String)
}

class ChatRepository(private val db: FirebaseFirestore) : ChatStore {

    constructor(context: Context) : this(
        FirebaseFirestore.getInstance(
            context.applicationContext.getString(R.string.firestore_database_id)
        )
    )

    override fun generateChatId(userId: String): String =
        db.collection("users")
            .document(userId)
            .collection("chats")
            .document()
            .id

    override fun observeChats(userId: String): Flow<List<ChatSession>> {
        val path = "users/$userId/chats"
        return db.collection("users")
            .document(userId)
            .collection("chats")
            .orderBy("updatedAt", Query.Direction.DESCENDING)
            .snapshots()
            .map { snapshot ->
                snapshot.toObjects(
                    ChatSession::class.java,
                    DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                )
            }
            .catch { error ->
                val firestoreError = generateSequence(error) { it.cause }
                    .filterIsInstance<FirebaseFirestoreException>()
                    .firstOrNull()
                if (firestoreError == null && error is CancellationException) throw error
                val exceptionToLog = firestoreError ?: (error as? Exception)
                if (exceptionToLog != null) {
                    handleFirestoreError(exceptionToLog, OperationType.LIST, path)
                }
                throw error
            }
    }

    override fun observeMessages(userId: String, chatId: String): Flow<List<ChatMessage>> {
        val path = "users/$userId/chats/$chatId/messages"
        return db.collection("users")
            .document(userId)
            .collection("chats")
            .document(chatId)
            .collection("messages")
            .orderBy("createdAt", Query.Direction.ASCENDING)
            .snapshots()
            .map { snapshot ->
                snapshot.toObjects(
                    ChatMessage::class.java,
                    DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                )
            }
            .catch { error ->
                val firestoreError = generateSequence(error) { it.cause }
                    .filterIsInstance<FirebaseFirestoreException>()
                    .firstOrNull()
                if (firestoreError == null && error is CancellationException) throw error
                val exceptionToLog = firestoreError ?: (error as? Exception)
                if (exceptionToLog != null) {
                    handleFirestoreError(exceptionToLog, OperationType.LIST, path)
                }
                throw error
            }
    }

    override suspend fun createChatWithFirstMessage(
        userId: String,
        title: String,
        languageHint: String,
        messageText: String,
        chatId: String
    ): String {
        val resolvedChatId = chatId.ifBlank { generateChatId(userId) }
        val chatRef = db.collection("users")
            .document(userId)
            .collection("chats")
            .document(resolvedChatId)
        val msgRef = chatRef.collection("messages").document()

        val safeTitle = title.trim().ifEmpty { "New Chat" }.take(120)
        val safePreview = messageText.trim().take(200)
        val safeText = messageText.trim().take(32000)

        val chatPayload = mapOf(
            "userId" to userId,
            "title" to safeTitle,
            "languageHint" to languageHint,
            "lastMessagePreview" to safePreview,
            "messageCount" to 1L,
            "createdAt" to FieldValue.serverTimestamp(),
            "updatedAt" to FieldValue.serverTimestamp()
        )
        val msgPayload = mapOf(
            "userId" to userId,
            "chatId" to chatRef.id,
            "role" to ChatMessage.ROLE_USER,
            "text" to safeText,
            "createdAt" to FieldValue.serverTimestamp()
        )

        val batch = db.batch()
        batch.set(chatRef, chatPayload)
        batch.set(msgRef, msgPayload)

        try {
            withTimeout(FIRESTORE_WRITE_TIMEOUT_MS) {
                batch.commit().await()
            }
            return chatRef.id
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, chatRef.path)
            throw e
        }
    }

    suspend fun createEmptyChat(
        userId: String,
        title: String = "New Chat",
        languageHint: String = "auto"
    ): String {
        val chatRef = db.collection("users")
            .document(userId)
            .collection("chats")
            .document()
        val payload = mapOf(
            "userId" to userId,
            "title" to title.trim().ifEmpty { "New Chat" }.take(120),
            "languageHint" to languageHint,
            "lastMessagePreview" to "",
            "messageCount" to 0L,
            "createdAt" to FieldValue.serverTimestamp(),
            "updatedAt" to FieldValue.serverTimestamp()
        )
        try {
            withTimeout(FIRESTORE_WRITE_TIMEOUT_MS) {
                chatRef.set(payload).await()
            }
            return chatRef.id
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, chatRef.path)
            throw e
        }
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
        val chatRef = db.collection("users")
            .document(userId)
            .collection("chats")
            .document(chatId)
        val msgRef = chatRef.collection("messages").document()

        val safeText = text.trim().take(32000)
        val safePreview = safeText.take(200)

        val msgPayload = mapOf(
            "userId" to userId,
            "chatId" to chatId,
            "role" to role,
            "text" to safeText,
            "createdAt" to FieldValue.serverTimestamp()
        )

        val chatUpdate = mutableMapOf<String, Any>(
            "lastMessagePreview" to safePreview,
            "messageCount" to newMessageCount.coerceIn(0L, 2000L),
            "updatedAt" to FieldValue.serverTimestamp()
        )
        if (!updatedTitle.isNullOrBlank()) {
            chatUpdate["title"] = updatedTitle.trim().take(120)
        }
        if (!languageHint.isNullOrBlank()) {
            chatUpdate["languageHint"] = languageHint
        }

        val batch = db.batch()
        batch.set(msgRef, msgPayload)
        batch.update(chatRef, chatUpdate)

        try {
            withTimeout(FIRESTORE_WRITE_TIMEOUT_MS) {
                batch.commit().await()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, msgRef.path)
            throw e
        }
    }

    override suspend fun updateLanguageHint(
        userId: String,
        chatId: String,
        languageHint: String
    ) {
        val chatRef = db.collection("users")
            .document(userId)
            .collection("chats")
            .document(chatId)
        try {
            withTimeout(FIRESTORE_WRITE_TIMEOUT_MS) {
                chatRef.update(
                    mapOf(
                        "languageHint" to languageHint,
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                ).await()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, chatRef.path)
            throw e
        }
    }

    override suspend fun clearChatMessages(userId: String, chatId: String) {
        val chatRef = db.collection("users")
            .document(userId)
            .collection("chats")
            .document(chatId)
        val messagesRef = chatRef.collection("messages")
        try {
            withTimeout(FIRESTORE_WRITE_TIMEOUT_MS) {
                val docs = messagesRef.get().await().documents
                docs.chunked(400).forEach { chunk ->
                    val batch = db.batch()
                    chunk.forEach { batch.delete(it.reference) }
                    batch.commit().await()
                }
                chatRef.update(
                    mapOf(
                        "title" to "New Chat",
                        "lastMessagePreview" to "",
                        "messageCount" to 0L,
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                ).await()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.DELETE, messagesRef.path)
            throw e
        }
    }

    override suspend fun deleteChat(userId: String, chatId: String) {
        val chatRef = db.collection("users")
            .document(userId)
            .collection("chats")
            .document(chatId)
        val messagesRef = chatRef.collection("messages")
        try {
            withTimeout(FIRESTORE_WRITE_TIMEOUT_MS) {
                val docs = messagesRef.get().await().documents
                docs.chunked(400).forEach { chunk ->
                    val batch = db.batch()
                    chunk.forEach { batch.delete(it.reference) }
                    batch.commit().await()
                }
                chatRef.delete().await()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.DELETE, chatRef.path)
            throw e
        }
    }

    private companion object {
        const val FIRESTORE_WRITE_TIMEOUT_MS = 12_000L
    }
}
