package com.example.ui.chat

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.withKeyDown
import com.example.ui.theme.AbhinavAiTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ChatInputAndComponentsTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun sendButton_triggersOnSendWhenInputNonBlank() {
        var input by mutableStateOf("")
        var sentCount = 0

        composeTestRule.setContent {
            AbhinavAiTheme {
                ChatInputBar(
                    inputText = input,
                    onInputChange = { input = it },
                    isSending = false,
                    remainingRequests = 30,
                    onSend = {
                        sentCount++
                        input = ""
                    },
                    onStop = {}
                )
            }
        }

        composeTestRule.onNodeWithTag("send_message_button").assertIsNotEnabled()
        composeTestRule.onNodeWithTag("chat_message_input").performTextInput("Hello Abhinav AI")
        composeTestRule.onNodeWithTag("send_message_button").assertIsEnabled()
        composeTestRule.onNodeWithTag("send_message_button").performClick()

        assertEquals(1, sentCount)
        assertEquals("", input)
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun enterSendsMessage_andShiftEnterInsertsNewline() {
        var input by mutableStateOf("")
        var sentPayload: String? = null

        composeTestRule.setContent {
            AbhinavAiTheme {
                ChatInputBar(
                    inputText = input,
                    onInputChange = { input = it },
                    isSending = false,
                    remainingRequests = 30,
                    onSend = {
                        sentPayload = input
                        input = ""
                    },
                    onStop = {}
                )
            }
        }

        val inputNode = composeTestRule.onNodeWithTag("chat_message_input")
        inputNode.performClick()
        inputNode.performTextInput("Line 1")

        // Shift+Enter should NOT send
        inputNode.performKeyInput {
            withKeyDown(Key.ShiftLeft) {
                pressKey(Key.Enter)
            }
        }
        composeTestRule.waitForIdle()
        assertEquals(null, sentPayload)

        // Plain Enter SHOULD send
        inputNode.performKeyInput {
            pressKey(Key.Enter)
        }
        composeTestRule.waitForIdle()
        assertTrue(sentPayload?.startsWith("Line 1") == true)
    }

    @Test
    fun thinkingIndicator_andErrorRetryBubble_renderCorrectly() {
        var retried = false

        composeTestRule.setContent {
            AbhinavAiTheme {
                androidx.compose.foundation.layout.Column {
                    ThinkingIndicatorBubble()
                    ErrorRetryBubble(
                        errorMessage = "Network error while contacting Gemini.",
                        onRetry = { retried = true }
                    )
                }
            }
        }

        composeTestRule.onNodeWithTag("ai_loading_indicator").assertIsDisplayed()
        composeTestRule.onNodeWithTag("error_retry_bubble").assertIsDisplayed()
        composeTestRule.onNodeWithText("Network error while contacting Gemini.").assertIsDisplayed()
        composeTestRule.onNodeWithTag("retry_message_button").performClick()
        assertTrue(retried)
    }
}
