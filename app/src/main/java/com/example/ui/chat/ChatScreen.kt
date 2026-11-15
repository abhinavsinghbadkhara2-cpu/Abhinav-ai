package com.example.ui.chat

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AddComment
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.PermanentDrawerSheet
import androidx.compose.material3.PermanentNavigationDrawer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.credentials.CredentialManager
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.R
import com.example.data.ai.GeminiChatService
import com.example.data.model.ChatMessage
import com.example.data.model.LanguageMode
import com.example.data.repository.ChatRepository
import com.example.ui.auth.signOutUser
import com.example.ui.theme.AbhinavBorderGradient
import com.example.ui.theme.ElectricBlue
import com.example.ui.theme.ElectricCyan
import com.example.ui.theme.ErrorContainerDark
import com.example.ui.theme.ErrorRed
import com.example.ui.theme.NavyCard
import com.example.ui.theme.NavyCardElevated
import com.example.ui.theme.NavyDeep
import com.example.ui.theme.NavyOutline
import com.example.ui.theme.NavySurface
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.ui.theme.VibrantPurple
import com.example.ui.viewmodel.ChatScreenUiState
import com.example.ui.viewmodel.ChatViewModel
import com.google.firebase.Firebase
import com.google.firebase.auth.auth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.launch

@Composable
fun ChatScreen(
    currentUserId: String
) {
    val context = LocalContext.current
    val credentialManager = remember(context) { CredentialManager.create(context) }
    val scope = rememberCoroutineScope()

    val viewModel: ChatViewModel = viewModel(
        key = currentUserId,
        factory = viewModelFactory {
            initializer {
                val app = checkNotNull(this[APPLICATION_KEY]) {
                    "APPLICATION_KEY missing from CreationExtras"
                }
                val databaseId = app.getString(R.string.firestore_database_id)
                val db = FirebaseFirestore.getInstance(databaseId)
                ChatViewModel(
                    repository = ChatRepository(db),
                    aiService = GeminiChatService(),
                    currentUserId = currentUserId
                )
            }
        }
    )

    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val chatsState by viewModel.chatsState.collectAsStateWithLifecycle()
    val messages by viewModel.messages.collectAsStateWithLifecycle()

    val user = Firebase.auth.currentUser
    val snackbarHostState = remember { SnackbarHostState() }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val isExpandedScreen = maxWidth >= 840.dp

        val drawerContent: @Composable (onCloseDrawer: () -> Unit) -> Unit = { onCloseDrawer ->
            ChatHistoryDrawerContent(
                chatsState = chatsState,
                activeChatId = uiState.activeChatId,
                languageMode = uiState.languageMode,
                userDisplayName = user?.displayName,
                userEmail = user?.email,
                onNewChat = {
                    viewModel.startNewChat()
                    onCloseDrawer()
                },
                onSelectChat = { chat ->
                    viewModel.selectChat(chat)
                    onCloseDrawer()
                },
                onDeleteChat = { chatId ->
                    viewModel.deleteChat(chatId)
                },
                onSelectLanguageMode = { mode ->
                    viewModel.setLanguageMode(mode)
                },
                onSignOut = {
                    onCloseDrawer()
                    signOutUser(credentialManager, scope)
                }
            )
        }

        if (isExpandedScreen) {
            PermanentNavigationDrawer(
                drawerContent = {
                    PermanentDrawerSheet(
                        drawerContainerColor = NavySurface,
                        drawerContentColor = TextPrimary
                    ) {
                        drawerContent({})
                    }
                }
            ) {
                ChatMainScaffold(
                    uiState = uiState,
                    persistedMessages = messages,
                    showMenuButton = false,
                    snackbarHostState = snackbarHostState,
                    onOpenDrawer = {},
                    onNewChat = { viewModel.startNewChat() },
                    onRequestClearChat = { viewModel.requestClearChat() },
                    onSelectLanguageMode = { viewModel.setLanguageMode(it) },
                    onInputChange = { viewModel.onInputChanged(it) },
                    onSend = { viewModel.sendMessage() },
                    onExampleClick = { viewModel.sendMessage(it) },
                    onRetry = { viewModel.retryLastMessage() },
                    onStop = { viewModel.stopGeneration() },
                    onDismissError = { viewModel.dismissError() },
                    onCopyFeedback = {
                        scope.launch {
                            snackbarHostState.currentSnackbarData?.dismiss()
                            snackbarHostState.showSnackbar("Copied response to clipboard")
                        }
                    }
                )
            }
        } else {
            val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)

            BackHandler(enabled = drawerState.isOpen) {
                scope.launch { drawerState.close() }
            }

            ModalNavigationDrawer(
                drawerState = drawerState,
                drawerContent = {
                    ModalDrawerSheet(
                        drawerContainerColor = NavySurface,
                        drawerContentColor = TextPrimary
                    ) {
                        drawerContent {
                            scope.launch { drawerState.close() }
                        }
                    }
                }
            ) {
                ChatMainScaffold(
                    uiState = uiState,
                    persistedMessages = messages,
                    showMenuButton = true,
                    snackbarHostState = snackbarHostState,
                    onOpenDrawer = { scope.launch { drawerState.open() } },
                    onNewChat = { viewModel.startNewChat() },
                    onRequestClearChat = { viewModel.requestClearChat() },
                    onSelectLanguageMode = { viewModel.setLanguageMode(it) },
                    onInputChange = { viewModel.onInputChanged(it) },
                    onSend = { viewModel.sendMessage() },
                    onExampleClick = { viewModel.sendMessage(it) },
                    onRetry = { viewModel.retryLastMessage() },
                    onStop = { viewModel.stopGeneration() },
                    onDismissError = { viewModel.dismissError() },
                    onCopyFeedback = {
                        scope.launch {
                            snackbarHostState.currentSnackbarData?.dismiss()
                            snackbarHostState.showSnackbar("Copied response to clipboard")
                        }
                    }
                )
            }
        }
    }

    if (uiState.showClearChatDialog) {
        AlertDialog(
            onDismissRequest = { viewModel.dismissClearChatDialog() },
            containerColor = NavyCard,
            titleContentColor = TextPrimary,
            textContentColor = TextSecondary,
            title = { Text("Clear this conversation?") },
            text = {
                Text("All messages in this chat will be permanently removed. You can start fresh anytime.")
            },
            confirmButton = {
                TextButton(
                    onClick = { viewModel.confirmClearChat() },
                    colors = ButtonDefaults.textButtonColors(contentColor = ErrorRed),
                    modifier = Modifier.testTag("confirm_clear_chat_button")
                ) {
                    Text("Clear")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { viewModel.dismissClearChatDialog() },
                    colors = ButtonDefaults.textButtonColors(contentColor = TextSecondary)
                ) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
private fun ChatMainScaffold(
    uiState: ChatScreenUiState,
    persistedMessages: List<ChatMessage>,
    showMenuButton: Boolean,
    snackbarHostState: SnackbarHostState,
    onOpenDrawer: () -> Unit,
    onNewChat: () -> Unit,
    onRequestClearChat: () -> Unit,
    onSelectLanguageMode: (LanguageMode) -> Unit,
    onInputChange: (String) -> Unit,
    onSend: () -> Unit,
    onExampleClick: (String) -> Unit,
    onRetry: () -> Unit,
    onStop: () -> Unit,
    onDismissError: () -> Unit,
    onCopyFeedback: () -> Unit
) {
    val listState = rememberLazyListState()

    val displayedMessages = remember(persistedMessages, uiState.localTurns) {
        if (uiState.localTurns.isEmpty()) {
            persistedMessages
        } else {
            val extra = uiState.localTurns.filter { localMsg ->
                persistedMessages.none { it.role == localMsg.role && it.text == localMsg.text }
            }
            persistedMessages + extra
        }
    }

    val hasErrorBubble = uiState.lastFailedPrompt != null && uiState.errorMessage != null

    LaunchedEffect(
        displayedMessages.size,
        uiState.streamingText,
        uiState.isWaitingFirstToken,
        hasErrorBubble
    ) {
        val totalItems = displayedMessages.size +
            (if (!uiState.streamingText.isNullOrBlank() || uiState.isWaitingFirstToken) 1 else 0) +
            (if (hasErrorBubble) 1 else 0)
        if (totalItems > 0) {
            listState.animateScrollToItem(totalItems - 1)
        }
    }

    Scaffold(
        containerColor = NavyDeep,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        topBar = {
            AbhinavTopBar(
                showMenuButton = showMenuButton,
                hasMessages = displayedMessages.isNotEmpty(),
                languageMode = uiState.languageMode,
                onOpenDrawer = onOpenDrawer,
                onNewChat = onNewChat,
                onRequestClearChat = onRequestClearChat,
                onCycleLanguageMode = {
                    val entries = LanguageMode.entries
                    val next = entries[(entries.indexOf(uiState.languageMode) + 1) % entries.size]
                    onSelectLanguageMode(next)
                }
            )
        },
        bottomBar = {
            ChatInputBar(
                inputText = uiState.inputText,
                onInputChange = onInputChange,
                isSending = uiState.isSending,
                remainingRequests = uiState.remainingRequestsInWindow,
                onSend = onSend,
                onStop = onStop
            )
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(NavyDeep)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.radialGradient(
                            colors = listOf(
                                ElectricBlue.copy(alpha = 0.08f),
                                VibrantPurple.copy(alpha = 0.05f),
                                Color.Transparent
                            ),
                            center = Offset(450f, 120f),
                            radius = 900f
                        )
                    )
            )

            Column(modifier = Modifier.fillMaxSize()) {
                AnimatedVisibility(visible = uiState.errorMessage != null && !hasErrorBubble) {
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        shape = RoundedCornerShape(14.dp),
                        color = ErrorContainerDark,
                        border = BorderStroke(1.dp, ErrorRed.copy(alpha = 0.45f))
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = uiState.errorMessage ?: "",
                                style = MaterialTheme.typography.bodySmall,
                                color = ErrorRed,
                                modifier = Modifier.weight(1f)
                            )
                            IconButton(
                                onClick = onDismissError,
                                modifier = Modifier.size(36.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Close,
                                    contentDescription = "Dismiss error",
                                    tint = ErrorRed,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                }

                if (displayedMessages.isEmpty() && !uiState.isWaitingFirstToken && uiState.streamingText.isNullOrBlank()) {
                    WelcomeScreen(
                        languageMode = uiState.languageMode,
                        onSelectLanguageMode = onSelectLanguageMode,
                        onExampleClick = onExampleClick,
                        modifier = Modifier.weight(1f)
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        contentAlignment = Alignment.TopCenter
                    ) {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier
                                .widthIn(max = 780.dp)
                                .fillMaxSize()
                                .testTag("chat_messages_list"),
                            contentPadding = PaddingValues(
                                horizontal = 14.dp,
                                vertical = 14.dp
                            ),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(displayedMessages, key = { it.id }) { msg ->
                                if (msg.isUser) {
                                    UserMessageBubble(text = msg.text)
                                } else {
                                    AiMessageBubble(
                                        text = msg.text,
                                        isStreaming = false,
                                        onCopySuccess = onCopyFeedback
                                    )
                                }
                            }

                            if (uiState.isWaitingFirstToken && uiState.streamingText.isNullOrEmpty()) {
                                item(key = "thinking_indicator") {
                                    ThinkingIndicatorBubble()
                                }
                            } else if (!uiState.streamingText.isNullOrBlank()) {
                                item(key = "streaming_bubble") {
                                    AiMessageBubble(
                                        text = uiState.streamingText,
                                        isStreaming = true
                                    )
                                }
                            }

                            if (hasErrorBubble) {
                                item(key = "error_retry_bubble") {
                                    ErrorRetryBubble(
                                        errorMessage = uiState.errorMessage ?: "Request failed.",
                                        onRetry = onRetry
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AbhinavTopBar(
    showMenuButton: Boolean,
    hasMessages: Boolean,
    languageMode: LanguageMode,
    onOpenDrawer: () -> Unit,
    onNewChat: () -> Unit,
    onRequestClearChat: () -> Unit,
    onCycleLanguageMode: () -> Unit
) {
    Surface(
        color = NavySurface.copy(alpha = 0.96f),
        border = BorderStroke(1.dp, NavyOutline.copy(alpha = 0.7f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (showMenuButton) {
                IconButton(
                    onClick = onOpenDrawer,
                    modifier = Modifier
                        .size(48.dp)
                        .testTag("open_history_drawer_button")
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Menu,
                        contentDescription = stringResource(id = R.string.open_menu),
                        tint = TextPrimary
                    )
                }
            } else {
                Spacer(modifier = Modifier.width(8.dp))
            }

            AbhinavBrandBadge(
                showSubtitle = false,
                modifier = Modifier.weight(1f)
            )

            Surface(
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .clickable(onClick = onCycleLanguageMode)
                    .testTag("topbar_language_toggle"),
                shape = RoundedCornerShape(50),
                color = NavyCardElevated,
                border = BorderStroke(1.dp, AbhinavBorderGradient)
            ) {
                Text(
                    text = languageMode.badge,
                    style = MaterialTheme.typography.labelSmall,
                    color = ElectricCyan,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                )
            }

            Spacer(modifier = Modifier.width(2.dp))

            if (hasMessages) {
                IconButton(
                    onClick = onRequestClearChat,
                    modifier = Modifier
                        .size(48.dp)
                        .testTag("clear_chat_button")
                ) {
                    Icon(
                        imageVector = Icons.Rounded.DeleteSweep,
                        contentDescription = stringResource(id = R.string.clear_chat),
                        tint = TextSecondary
                    )
                }
            }

            IconButton(
                onClick = onNewChat,
                modifier = Modifier
                    .size(48.dp)
                    .testTag("topbar_new_chat_button")
            ) {
                Icon(
                    imageVector = Icons.Rounded.AddComment,
                    contentDescription = stringResource(id = R.string.new_chat),
                    tint = ElectricCyan
                )
            }
        }
    }
}
