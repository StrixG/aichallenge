package me.obrekht.wishu.ui

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import me.obrekht.wishu.BuildConfig
import me.obrekht.wishu.MainActivity
import me.obrekht.wishu.R
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Visible end-to-end demo of history compression, driven through the real app UI (so it can be
 * screen-recorded). Opens the chat, sends a 7-turn Russian gift-brainstorm conversation against the
 * live DeepSeek API with compression ON (the default), and verifies the UI shows the fold happening:
 * once the raw tail outgrows the window (turn 6) the agent folds older turns into the summary and the
 * assistant bubble renders the "🗜 Folded older messages into the summary" note. The top-bar token
 * meter climbs every turn so the token cost is visible on screen throughout.
 *
 * Real-network test: needs a configured key + connectivity. Streaming replies mean Compose is never
 * "idle", so each turn waits on a visible completion signal — the top-bar cumulative token meter,
 * which is always on screen (unlike per-turn bubbles, which the LazyColumn virtualizes off-screen)
 * and ticks up exactly once per completed turn.
 */
@RunWith(AndroidJUnit4::class)
class ChatCompressionUiTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    // recent grows +2 per completed turn; it crosses FOLD_THRESHOLD (10) after the 6th turn, so the
    // 6th assistant bubble carries the fold note. Turn 7 continues after the fold.
    private val script = listOf(
        "Привет! Помоги придумать подарок для моей сестры.",
        "Ей 28 лет, она любит готовить и ходить в походы.",
        "Бюджет примерно 5000 рублей.",
        "Она не любит косметику и сладкое.",
        "Что-нибудь для кухни посоветуешь?",
        "А что подойдёт для походов?",
        "Напомни, какой у неё бюджет и что она любит?"
    )

    @Test
    fun compressionFoldIsVisibleInChatUi() {
        assumeTrue("DEEPSEEK_API_KEY not set — skipping live UI test", BuildConfig.DEEPSEEK_API_KEY.isNotBlank())
        val ctx = composeRule.activity

        // Wishlist -> chat via the AutoAwesome top-bar button.
        composeRule.onNodeWithContentDescription(ctx.getString(R.string.cd_open_chat)).performClick()

        script.forEachIndexed { index, message ->
            val turn = index + 1
            val tokensBefore = cumulativeTokens()

            composeRule.onNode(hasSetTextAction()).performTextInput(message)
            composeRule.onNodeWithContentDescription(ctx.getString(R.string.cd_send)).performClick()

            // The cumulative meter ticks up once when the turn's reply finishes streaming. Waiting on
            // it (always on screen) avoids the LazyColumn virtualization trap of counting per-turn
            // bubbles, whose token lines scroll out of the semantics tree.
            composeRule.waitUntil(timeoutMillis = 90_000) { cumulativeTokens() > tokensBefore }

            // Turn 6 is the one that folds older messages — its bubble (still on screen) must show
            // the compression note. Proof the fold actually happened, in the UI.
            if (turn == 6) {
                composeRule.onNodeWithText(ctx.getString(R.string.chat_summarized)).assertExists()
            }
        }

        // The session token meter is shown and has been accumulating across the dialog.
        composeRule.onNodeWithText(TOKEN_METER_MARKER, substring = true).assertExists()
    }

    // Read the integer from the top-bar "Σ N tokens this session · $…" meter, or 0 before any turn.
    private fun cumulativeTokens(): Long {
        val nodes = composeRule.onAllNodesWithText(TOKEN_METER_MARKER, substring = true).fetchSemanticsNodes()
        val text = nodes.firstOrNull()
            ?.config?.getOrNull(SemanticsProperties.Text)
            ?.joinToString("") { it.text } ?: return 0
        // First digit run is the token count; ignore the trailing "$0.000xx" cost.
        return Regex("\\d[\\d ,. ]*").find(text)?.value?.filter { it.isDigit() }?.toLongOrNull() ?: 0
    }

    private companion object {
        const val TOKEN_METER_MARKER = "tokens this session"
    }
}
