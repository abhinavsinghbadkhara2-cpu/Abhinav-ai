package com.example.data.model

import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.IgnoreExtraProperties
import com.google.firebase.firestore.ServerTimestamp

enum class LanguageMode(
    val code: String,
    val label: String,
    val badge: String
) {
    AUTO("auto", "Auto (EN / हिंदी)", "EN • हिं"),
    ENGLISH("en", "English", "EN"),
    HINDI("hi", "हिंदी (Hindi)", "हिंदी");

    companion object {
        fun fromCode(code: String?): LanguageMode =
            entries.firstOrNull { it.code == code } ?: AUTO
    }
}

@IgnoreExtraProperties
data class ChatSession(
    @DocumentId val id: String = "",
    val userId: String = "",
    val title: String = "New Chat",
    val languageHint: String = "auto",
    val lastMessagePreview: String = "",
    val messageCount: Long = 0L,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@IgnoreExtraProperties
data class ChatMessage(
    @DocumentId val id: String = "",
    val userId: String = "",
    val chatId: String = "",
    val role: String = "user",
    val text: String = "",
    @ServerTimestamp val createdAt: Timestamp? = null
) {
    val isUser: Boolean
        get() = role == ROLE_USER

    companion object {
        const val ROLE_USER = "user"
        const val ROLE_MODEL = "model"
    }
}
