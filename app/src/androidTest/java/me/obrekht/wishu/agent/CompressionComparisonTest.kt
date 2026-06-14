package me.obrekht.wishu.agent

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.runBlocking
import me.obrekht.wishu.BuildConfig
import okhttp3.OkHttpClient
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Day 9 demonstration: run the SAME Russian conversation through two agents — one with history
 * compression OFF (full transcript re-sent every turn, the uncompressed baseline) and one with it
 * ON (older turns folded into a running summary) — and compare real DeepSeek token usage.
 *
 * Real-network test: it hits the live DeepSeek API, so it needs a configured key and a device with
 * connectivity. Uses the cheap flash model; total cost is a fraction of a cent. Read Logcat tags
 * `TokenLedger` (two per-turn tables) and `CompressionTest` (replies + the savings diff) to see the
 * answer-quality and token-usage comparison the task asks for.
 */
@RunWith(AndroidJUnit4::class)
class CompressionComparisonTest {

    private val model = "deepseek-v4-flash"

    // 8 user turns. recent grows +2 per completed turn, so it crosses FOLD_THRESHOLD (10) after the
    // 6th turn — the compressed agent folds at least once. Turn 7 deliberately asks the model to
    // recall facts from the earliest (now-folded) turns, probing that the summary preserved context.
    private val script = listOf(
        "Привет! Помоги придумать подарок для моей сестры.",
        "Ей 28 лет, она любит готовить и ходить в походы.",
        "Бюджет примерно 5000 рублей.",
        "Она не любит косметику и сладкое.",
        "Что-нибудь для кухни посоветуешь?",
        "А что подойдёт для походов?",
        "Напомни, какой у неё бюджет и что она любит?",
        "Давай ещё пару идей в пределах бюджета."
    )

    @Test
    fun compressionSavesTokensAndKeepsContext() {
        assumeTrue("DEEPSEEK_API_KEY not set — skipping live test", BuildConfig.DEEPSEEK_API_KEY.isNotBlank())

        val client = buildClient()

        Log.i(TAG, "===== BASELINE (compression OFF) =====")
        val baselineAgent = WishChatAgent(client)
        val baseline = runConversation(baselineAgent, compress = false)

        Log.i(TAG, "===== COMPRESSED (compression ON) =====")
        val compressedAgent = WishChatAgent(client)
        val compressed = runConversation(compressedAgent, compress = true)

        val baseLast = baseline.last()
        val compLast = compressed.last()

        Log.i(TAG, "===== COMPARISON =====")
        Log.i(TAG, "last-turn prompt tokens   baseline=${baseLast.promptActual}  compressed=${compLast.promptActual}")
        Log.i(TAG, "cumulative total tokens   baseline=${baseLast.cumulativeTotal}  compressed=${compLast.cumulativeTotal}")
        Log.i(
            TAG,
            "cumulative cost (USD)     baseline=%.5f  compressed=%.5f".format(
                baseLast.cumulativeCostUsd, compLast.cumulativeCostUsd
            )
        )
        val saved = baseLast.cumulativeTotal - compLast.cumulativeTotal
        val pct = 100.0 * saved / baseLast.cumulativeTotal
        Log.i(TAG, "tokens saved by compression = $saved (%.1f%%)".format(pct))
        Log.i(TAG, "summary produced: summarizedCount=${compressedAgent.summarizedCount}, summary=${compressedAgent.summary}")

        // A fold actually happened: older turns were compressed into the running summary.
        assertNotNull("compression should have produced a running summary", compressedAgent.summary)
        assertTrue("compression should have folded older turns", compressedAgent.summarizedCount > 0)

        // The core of the demo: the baseline prompt grows with the whole transcript, while the
        // compressed prompt stays bounded (system + summary + recent tail).
        assertTrue(
            "compressed last-turn prompt (${compLast.promptActual}) should be smaller than " +
                "baseline (${baseLast.promptActual})",
            compLast.promptActual < baseLast.promptActual
        )

        // NOTE: net cumulative savings are NOT asserted — they're length-dependent. Compression pays
        // a one-off summary-call cost plus re-sends the summary every turn, so on a short dialog it
        // can cost MORE in total even though each prompt is smaller. The win is bounded prompt growth
        // (baseline grows with the whole transcript -> quadratic cumulative; compressed stays linear),
        // so the crossover to net savings only appears in long chats. We log the comparison instead.
        if (compLast.cumulativeTotal < baseLast.cumulativeTotal) {
            Log.i(TAG, "compression is already net-cheaper at ${script.size} turns")
        } else {
            Log.i(
                TAG,
                "compression not yet net-cheaper at ${script.size} turns (summary overhead not amortised); " +
                    "per-turn prompt is already bounded, so savings grow with dialog length"
            )
        }
    }

    // Drive one full conversation, draining each streamed reply. Logs every reply so answer quality
    // can be compared by eye between the two runs. Returns the per-turn token ledger.
    private fun runConversation(agent: WishChatAgent, compress: Boolean): List<TurnTokens> = runBlocking {
        val label = if (compress) "ON " else "OFF"
        script.forEachIndexed { index, message ->
            val reply = StringBuilder()
            agent.send(message, model, compress).collect { event ->
                when (event) {
                    is ChatEvent.Token -> reply.append(event.delta)
                    is ChatEvent.Complete ->
                        Log.i(
                            TAG,
                            "[$label] turn ${index + 1} prompt=${event.tokens.promptActual} " +
                                "reply=${event.tokens.completionActual} folded=${event.summarized}"
                        )
                }
            }
            Log.i(TAG, "[$label] turn ${index + 1} USER: $message")
            Log.i(TAG, "[$label] turn ${index + 1} ASSISTANT: $reply")
        }
        agent.ledger.turns.toList()
    }

    // Mirror WishuApplication.baseClientBuilder(): retry + bearer auth + timeouts, no body logging.
    private fun buildClient(): OkHttpClient =
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .callTimeout(300, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                var lastError: IOException? = null
                repeat(3) {
                    try {
                        return@addInterceptor chain.proceed(chain.request())
                    } catch (e: IOException) {
                        lastError = e
                    }
                }
                throw lastError ?: IOException("request failed")
            }
            .addInterceptor { chain ->
                chain.proceed(
                    chain.request().newBuilder()
                        .addHeader("Authorization", "Bearer ${BuildConfig.DEEPSEEK_API_KEY}")
                        .build()
                )
            }
            .build()

    private companion object {
        const val TAG = "CompressionTest"
    }
}
