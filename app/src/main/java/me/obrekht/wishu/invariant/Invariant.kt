package me.obrekht.wishu.invariant

/**
 * Invariants (Day 14): rules the assistant may NEVER break — architecture, stack, or business rules,
 * here adapted to the gift-assistant domain ("never suggest alcohol", "stay within budget"). They live
 * OUTSIDE the chat transcript (their own Room table), are mixed into every turn's reasoning, and are
 * enforced in two layers (deterministic Kotlin + an LLM validator). On conflict the assistant refuses,
 * names the broken invariant, and offers an alternative within the constraint.
 */

/** Which enforcement layer(s) check this invariant. */
enum class CheckType { DETERMINISTIC, LLM, BOTH }

/** HARD = a flat refusal; SOFT = a warning the user can override with confirmation. */
enum class Severity { HARD, SOFT }

/**
 * The kind of built-in deterministic check (pure Kotlin, no network). Free-text rules use [NONE] and
 * are left to the LLM validator; the typed kinds are code-checkable without crude `String.contains`.
 */
enum class DetKind {
    NONE,
    BUDGET_CEILING,   // parse price-like amounts from the text vs the working-memory budget fact
    BANNED_KEYWORDS   // tokenized, word-boundary, normalized match against [Invariant.keywords]
}

data class Invariant(
    val id: String,
    val rule: String,            // the human formulation (also handed to the LLM validator)
    val check: CheckType,
    val severity: Severity,
    val refusalReason: String,   // shown to the user when the invariant is violated
    val detKind: DetKind = DetKind.NONE,
    val keywords: List<String> = emptyList() // used by BANNED_KEYWORDS
) {
    val usesDeterministic: Boolean get() = check == CheckType.DETERMINISTIC || check == CheckType.BOTH
    val usesLlm: Boolean get() = check == CheckType.LLM || check == CheckType.BOTH
}

/** The result of checking a single invariant against some text. */
sealed interface InvariantResult {
    data object Passed : InvariantResult
    data class Violated(val invariant: Invariant, val explanation: String) : InvariantResult
}
