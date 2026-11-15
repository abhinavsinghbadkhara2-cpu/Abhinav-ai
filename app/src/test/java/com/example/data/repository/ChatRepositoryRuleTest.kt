package com.example.data.repository

import com.example.base.FirestoreEmulatorTestBase
import com.example.data.model.ChatMessage
import com.google.firebase.firestore.FirebaseFirestoreException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ChatRepositoryRuleTest : FirestoreEmulatorTestBase() {

    @Test
    fun createChatWithFirstMessage_authenticatedOwner_persistsAndObservesBoth() = runBlocking {
        val uid = signInTestUser(ALICE_EMAIL)
        val repository = ChatRepository(firestore)

        val chatId = withTimeout(DEFAULT_TIMEOUT_MS) {
            repository.createChatWithFirstMessage(
                userId = uid,
                title = "Hindi Greeting",
                languageHint = "hi",
                messageText = "नमस्ते अभिनव AI!"
            )
        }
        assertTrue(chatId.isNotBlank())

        val chats = withTimeout(FLOW_TIMEOUT_MS) {
            repository.observeChats(uid).first { list -> list.any { it.id == chatId } }
        }
        assertTrue(chats.any { it.id == chatId && it.title == "Hindi Greeting" })

        val messages = withTimeout(FLOW_TIMEOUT_MS) {
            repository.observeMessages(uid, chatId).first { it.isNotEmpty() }
        }
        assertEquals(1, messages.size)
        assertEquals("नमस्ते अभिनव AI!", messages.first().text)
        assertEquals(ChatMessage.ROLE_USER, messages.first().role)
    }

    @Test
    fun appendAndClearMessages_authenticatedOwner_updatesAndClears() = runBlocking {
        val uid = signInTestUser(ALICE_EMAIL)
        val repository = ChatRepository(firestore)

        val chatId = withTimeout(DEFAULT_TIMEOUT_MS) {
            repository.createChatWithFirstMessage(
                userId = uid,
                title = "Science Question",
                languageHint = "en",
                messageText = "Explain black holes simply"
            )
        }

        withTimeout(DEFAULT_TIMEOUT_MS) {
            repository.appendMessage(
                userId = uid,
                chatId = chatId,
                role = ChatMessage.ROLE_MODEL,
                text = "A black hole is a region where gravity is so strong nothing escapes.",
                newMessageCount = 2L
            )
        }

        val messages = withTimeout(FLOW_TIMEOUT_MS) {
            repository.observeMessages(uid, chatId).first { it.size == 2 }
        }
        assertEquals(2, messages.size)

        withTimeout(DEFAULT_TIMEOUT_MS) {
            repository.clearChatMessages(uid, chatId)
        }

        val clearedMessages = withTimeout(FLOW_TIMEOUT_MS) {
            repository.observeMessages(uid, chatId).first { it.isEmpty() }
        }
        assertTrue(clearedMessages.isEmpty())
    }

    @Test
    fun observeChats_crossUserAccess_failsWithPermissionDenied() = runBlocking {
        val aliceUid = signInTestUser(ALICE_EMAIL)
        val repository = ChatRepository(firestore)
        withTimeout(DEFAULT_TIMEOUT_MS) {
            repository.createEmptyChat(aliceUid, title = "Alice Private Chat")
        }

        signInTestUser(BOB_EMAIL)
        try {
            withTimeout(FLOW_TIMEOUT_MS) {
                repository.observeChats(aliceUid).first()
            }
            fail("Expected PERMISSION_DENIED when Bob observes Alice's chats")
        } catch (e: Exception) {
            val firestoreEx = checkNotNull(e.unwrapFirestoreException()) {
                "Expected FirebaseFirestoreException, got $e"
            }
            assertEquals(FirebaseFirestoreException.Code.PERMISSION_DENIED, firestoreEx.code)
        }
    }

    @Test
    fun observeChats_unauthenticatedUser_failsWithPermissionDenied() = runBlocking {
        auth.signOut()
        val repository = ChatRepository(firestore)

        try {
            withTimeout(FLOW_TIMEOUT_MS) {
                repository.observeChats("alice_uid").first()
            }
            fail("Expected PERMISSION_DENIED when unauthenticated")
        } catch (e: Exception) {
            val firestoreEx = checkNotNull(e.unwrapFirestoreException()) {
                "Expected FirebaseFirestoreException, got $e"
            }
            assertEquals(FirebaseFirestoreException.Code.PERMISSION_DENIED, firestoreEx.code)
        }
    }

    private companion object {
        const val ALICE_EMAIL = "alice_abhinav@test.com"
        const val BOB_EMAIL = "bob_abhinav@test.com"
        const val DEFAULT_TIMEOUT_MS = 5000L
        const val FLOW_TIMEOUT_MS = 3000L
    }
}
