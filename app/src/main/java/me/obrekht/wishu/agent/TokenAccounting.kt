package me.obrekht.wishu.agent

import android.util.Log
import me.obrekht.wishu.invariant.Invariant
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
 * `aux*` are the tokens spent on an *extra* helper call some strategies make this turn (Summary
 * folding old turns into the running summary, or Sticky Facts refreshing the facts block); 0 on
 * turns with no helper call. They're the honest overhead of those strategies — bound the chat
 * prompt, but pay a periodic helper-call cost. Already included in `turnCostUsd` / `cumulativeCostUsd`.
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
    val auxPromptTokens: Int = 0,
    val auxCompletionTokens: Int = 0
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
     * Record a completed turn from DeepSeek's exact [usage]. [auxUsage] is the usage of an extra
     * helper call made on this turn (summary fold or facts refresh; null when none); it's always
     * the flash model, so its cost is priced at flash rates and added on top of the turn cost.
     */
    fun record(model: String, usage: Usage, auxUsage: Usage? = null): TurnTokens {
        val promptActual = usage.promptTokens
        val completionActual = usage.completionTokens
        val totalActual = usage.totalTokens
        val cacheHit = usage.promptCacheHitTokens
        val cacheMiss = usage.promptCacheMissTokens

        val auxCost = auxUsage?.let {
            costUsd(it.promptCacheHitTokens, it.promptCacheMissTokens, it.completionTokens, pricingFor(SUMMARY_MODEL))
        } ?: 0.0

        val cumulativeTotal = (_turns.lastOrNull()?.cumulativeTotal ?: 0) + totalActual +
            (auxUsage?.totalTokens ?: 0)
        val turnCost = costUsd(cacheHit, cacheMiss, completionActual, pricingFor(model)) + auxCost
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
            auxPromptTokens = auxUsage?.promptTokens ?: 0,
            auxCompletionTokens = auxUsage?.completionTokens ?: 0
        )
        _turns.add(turn)
        logRow(turn)
        return turn
    }

    private fun logRow(t: TurnTokens) {
        if (t.turn == 1) {
            Log.i(TAG, "turn | prompt | cHit | cMiss | reply | total | auxP | auxC | cumTotal | turn\$ | cum\$")
        }
        Log.i(
            TAG,
            "%4d | %6d | %5d | %5d | %5d | %5d | %4d | %4d | %8d | %.5f | %.5f".format(
                t.turn, t.promptActual, t.cacheHitActual, t.cacheMissActual,
                t.completionActual, t.totalActual, t.auxPromptTokens, t.auxCompletionTokens,
                t.cumulativeTotal, t.turnCostUsd, t.cumulativeCostUsd
            )
        )
    }

    private companion object {
        const val TAG = "TokenLedger"
    }
}

/** The extra helper call (if any) a strategy made on a turn — drives the panel's note + persistence. */
enum class AuxKind { NONE, SUMMARY, FACTS }

/** Streamed agent output: a content delta, or the final per-turn token accounting. */
sealed interface ChatEvent {
    data class Token(val delta: String) : ChatEvent
    // Emitted pre-stream, right after the task-state machine advances, so the UI stage badge flips
    // before the reply streams instead of after it completes.
    data class TaskAdvanced(val state: TaskState) : ChatEvent
    // Emitted after the reply finishes streaming, while the memory-layer helper calls run.
    data object MemoryUpdating : ChatEvent
    // `aux` says which helper call (if any) ran this turn: a summary fold or a facts refresh.
    data class Complete(val tokens: TurnTokens, val aux: AuxKind = AuxKind.NONE) : ChatEvent

    // Day 14: a HARD invariant was violated (by the request pre-stream, or the reply post-stream).
    // The turn is refused: no reply is committed. [rule] is the broken rule restated in the user's
    // language (falls back to the raw English [Invariant.rule] when the helper call fails);
    // [alternative] is a best-effort in-constraint suggestion (null if none could be generated).
    data class Refused(
        val invariant: Invariant,
        val rule: String,
        val explanation: String,
        val alternative: String?
    ) : ChatEvent

    // Day 14: a SOFT invariant was touched. [preGate] true = caught on the request before generating
    // (the UI asks the user to confirm before proceeding); false = caught on the reply (a warning
    // banner; the reply still stands).
    data class SoftViolation(
        val invariant: Invariant,
        val explanation: String,
        val preGate: Boolean
    ) : ChatEvent
}
