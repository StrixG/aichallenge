package me.obrekht.wishu.agent

import android.util.Log
import me.obrekht.wishu.network.Usage

/**
 * Token accounting for the chat agent.
 *
 * Every count here is DeepSeek's exact [Usage] from the final SSE chunk (requires
 * stream_options.include_usage=true) — no on-device heuristic. The whole conversation is re-sent
 * every turn, so prompt tokens — and therefore cost — grow with the dialog. That super-linear
 * growth is the thing this instrumentation makes visible.
 */

// DeepSeek V4 pricing (USD per 1M tokens) per model. Input is Сsplit: cache hits (repeated
// prefix, e.g. the system prompt + prior turns) bill far cheaper than cache misses.
data class ModelPricing(
    val inputCacheHitPer1M: Double,
    val inputCacheMissPer1M: Double,
    val outputPer1M: Double
)

private val PRICING = mapOf(
    "deepseek-v4-flash" to ModelPricing(0.0028, 0.14, 0.28),
    "deepseek-v4-pro" to ModelPricing(0.003625, 0.435, 0.87)
)
private val DEFAULT_PRICING = PRICING.getValue("deepseek-v4-flash")

fun pricingFor(model: String): ModelPricing = PRICING[model] ?: DEFAULT_PRICING

private fun costUsd(
    cacheHitTokens: Int,
    cacheMissTokens: Int,
    completionTokens: Int,
    pricing: ModelPricing
): Double =
    cacheHitTokens / 1_000_000.0 * pricing.inputCacheHitPer1M +
        cacheMissTokens / 1_000_000.0 * pricing.inputCacheMissPer1M +
        completionTokens / 1_000_000.0 * pricing.outputPer1M

/**
 * One turn's accounting. Every field is DeepSeek's exact count; `cumulative*` sum across the dialog.
 * `prompt` = the whole payload sent that turn (grows every turn); `cacheHit + cacheMiss == prompt`.
 *
 * `summary*` are the tokens spent on the *extra* summarization call when this turn folded older
 * messages into the running summary (0 on turns with no fold). They're the honest cost of
 * compression — bound the chat prompt, but pay a periodic summary-call overhead. Their cost is
 * already included in `turnCostUsd` / `cumulativeCostUsd`.
 */
data class TurnTokens(
    val turn: Int,
    val promptActual: Int,
    val cacheHitActual: Int,
    val cacheMissActual: Int,
    val completionActual: Int,
    val totalActual: Int,
    val cumulativeTotal: Int,
    val turnCostUsd: Double,
    val cumulativeCostUsd: Double,
    val summaryPromptTokens: Int = 0,
    val summaryCompletionTokens: Int = 0
)

/**
 * Accumulates per-turn token counts across a dialog and logs a running table to Logcat.
 * Lives alongside the agent's in-memory history; not persisted, so it resets each session.
 */
class TokenLedger {
    private val _turns = mutableListOf<TurnTokens>()
    val turns: List<TurnTokens> get() = _turns

    fun clear() = _turns.clear()

    /**
     * Record a completed turn from DeepSeek's exact [usage]. [summaryUsage] is the usage of the
     * extra summarization call made on this turn (null when no fold happened); it's always the
     * flash model, so its cost is priced at flash rates and added on top of the turn cost.
     */
    fun record(model: String, usage: Usage, summaryUsage: Usage? = null): TurnTokens {
        val promptActual = usage.promptTokens
        val completionActual = usage.completionTokens
        val totalActual = usage.totalTokens
        val cacheHit = usage.promptCacheHitTokens
        val cacheMiss = usage.promptCacheMissTokens

        val summaryCost = summaryUsage?.let {
            costUsd(it.promptCacheHitTokens, it.promptCacheMissTokens, it.completionTokens, pricingFor(SUMMARY_MODEL))
        } ?: 0.0

        val cumulativeTotal = (_turns.lastOrNull()?.cumulativeTotal ?: 0) + totalActual +
            (summaryUsage?.totalTokens ?: 0)
        val turnCost = costUsd(cacheHit, cacheMiss, completionActual, pricingFor(model)) + summaryCost
        val cumulativeCost = (_turns.lastOrNull()?.cumulativeCostUsd ?: 0.0) + turnCost

        val turn = TurnTokens(
            turn = _turns.size + 1,
            promptActual = promptActual,
            cacheHitActual = cacheHit,
            cacheMissActual = cacheMiss,
            completionActual = completionActual,
            totalActual = totalActual,
            cumulativeTotal = cumulativeTotal,
            turnCostUsd = turnCost,
            cumulativeCostUsd = cumulativeCost,
            summaryPromptTokens = summaryUsage?.promptTokens ?: 0,
            summaryCompletionTokens = summaryUsage?.completionTokens ?: 0
        )
        _turns.add(turn)
        logRow(turn)
        return turn
    }

    private fun logRow(t: TurnTokens) {
        if (t.turn == 1) {
            Log.i(TAG, "turn | prompt | cHit | cMiss | reply | total | sumP | sumC | cumTotal | turn\$ | cum\$")
        }
        Log.i(
            TAG,
            "%4d | %6d | %5d | %5d | %5d | %5d | %4d | %4d | %8d | %.5f | %.5f".format(
                t.turn, t.promptActual, t.cacheHitActual, t.cacheMissActual,
                t.completionActual, t.totalActual, t.summaryPromptTokens, t.summaryCompletionTokens,
                t.cumulativeTotal, t.turnCostUsd, t.cumulativeCostUsd
            )
        )
    }

    private companion object {
        const val TAG = "TokenLedger"
    }
}

/** Streamed agent output: a content delta, or the final per-turn token accounting. */
sealed interface ChatEvent {
    data class Token(val delta: String) : ChatEvent
    // `summarized` is true on a turn where older messages were folded into the running summary.
    data class Complete(val tokens: TurnTokens, val summarized: Boolean = false) : ChatEvent
}
