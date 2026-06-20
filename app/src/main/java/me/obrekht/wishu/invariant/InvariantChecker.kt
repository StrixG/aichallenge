package me.obrekht.wishu.invariant

/**
 * Deterministic invariant layer: pure Kotlin, no network. Handles the typed [DetKind]s that are
 * expressible in code — meaningfully, NOT via crude `String.contains` (which false-flags "java" inside
 * "javascript"). Free-text rules (DetKind.NONE) are left to the [InvariantValidator] LLM layer.
 *
 * Stateless and side-effect-free, so it unit-tests in isolation.
 */
object InvariantChecker {

    /**
     * Check [text] against the deterministic part of [invariants]. [budgetFact] is the working-memory
     * `budget` value (e.g. "~3000 RUB", "до 3000 ₽"); null when no budget is known yet. Returns one
     * [InvariantResult.Violated] per broken invariant (empty == all clear).
     */
    fun check(
        text: String,
        budgetFact: String?,
        invariants: List<Invariant>
    ): List<InvariantResult.Violated> = invariants
        .filter { it.usesDeterministic }
        .mapNotNull { inv ->
            when (inv.detKind) {
                DetKind.BANNED_KEYWORDS -> bannedKeyword(text, inv)
                DetKind.BUDGET_CEILING -> overBudget(text, budgetFact, inv)
                DetKind.NONE -> null
            }
        }

    // ---- BANNED_KEYWORDS -------------------------------------------------------------------------

    private fun bannedKeyword(text: String, inv: Invariant): InvariantResult.Violated? {
        val textTokens = tokenize(text).map(::stem)
        if (textTokens.isEmpty()) return null
        val hit = inv.keywords.firstOrNull { keyword ->
            val keyTokens = tokenize(keyword).map(::stem)
            keyTokens.isNotEmpty() && containsSubsequence(textTokens, keyTokens)
        } ?: return null
        return InvariantResult.Violated(
            inv,
            "Mentions \"$hit\", which the invariant forbids: ${inv.rule}"
        )
    }

    // Split into lowercased word tokens on any non letter/digit boundary, so punctuation, case, and
    // spacing don't matter. "Java." / "JAVA" -> "java"; but "javascript" stays a single token that
    // never equals "java" — no substring false positives.
    private fun tokenize(text: String): List<String> =
        text.lowercase()
            .split(Regex("[^\\p{L}\\p{N}]+"))
            .filter { it.isNotBlank() }

    // Crude Russian stemmer: strips one inflectional ending so declensions of a keyword still match
    // ("вино"/"вина"/"вином" -> "вин"; "сигареты"/"сигарет" -> "сигарет"). Only Cyrillic tokens are
    // stemmed — ASCII keeps EXACT matching so the substring guard holds (java != javascript, never
    // over-stripped to a shared stem). Min-stem length keeps short roots from collapsing, and long words
    // whose tail isn't an ending stay whole ("виноград"/grapes won't reduce to "вин"). Tradeoff: a few
    // homographs (e.g. "вина" = guilt) can false-positive — acceptable for a HARD gift-domain block.
    private fun stem(token: String): String {
        if (token.none { it in 'а'..'я' || it == 'ё' }) return token
        for (ending in RU_ENDINGS) {
            if (token.length - ending.length >= RU_MIN_STEM && token.endsWith(ending)) {
                return token.dropLast(ending.length)
            }
        }
        return token
    }

    // True if [needle]'s tokens appear as a contiguous run in [haystack] (handles multi-word keywords).
    private fun containsSubsequence(haystack: List<String>, needle: List<String>): Boolean {
        if (needle.size > haystack.size) return false
        for (start in 0..haystack.size - needle.size) {
            var match = true
            for (i in needle.indices) {
                if (haystack[start + i] != needle[i]) { match = false; break }
            }
            if (match) return true
        }
        return false
    }

    // ---- BUDGET_CEILING --------------------------------------------------------------------------

    private fun overBudget(text: String, budgetFact: String?, inv: Invariant): InvariantResult.Violated? {
        val ceiling = budgetFact?.let { parseCeiling(it) } ?: return null // no budget known -> can't judge
        val amounts = parseAmounts(text)
        val over = amounts.firstOrNull { it > ceiling } ?: return null
        return InvariantResult.Violated(
            inv,
            "Proposes ${formatAmount(over)}, above the ${formatAmount(ceiling)} budget: ${inv.rule}"
        )
    }

    // The budget ceiling = the largest plain number in the budget fact (so a "2000-3000" range -> 3000).
    private fun parseCeiling(budgetFact: String): Double? =
        NUMBER.findAll(budgetFact)
            .mapNotNull { it.value.normalizeNumber() }
            .maxOrNull()

    // Price-like amounts in free text: a number adjacent to a currency marker on either side. Requiring
    // a currency keeps plain quantities/years ("2 sets", "in 2024") from being read as prices.
    private fun parseAmounts(text: String): List<Double> {
        val lower = text.lowercase()
        return (AMOUNT_BEFORE.findAll(lower) + AMOUNT_AFTER.findAll(lower))
            .mapNotNull { it.groupValues[1].normalizeNumber() }
            .toList()
    }

    private fun String.normalizeNumber(): Double? {
        // Drop spaces used as thousands separators; treat a comma as a decimal/thousands sep by removing
        // it when it groups three trailing digits, else as a decimal point.
        val cleaned = trim().replace(" ", "")
        val dotted = when {
            cleaned.contains(',') && cleaned.substringAfterLast(',').length == 3 ->
                cleaned.replace(",", "")            // 1,500 -> 1500 (thousands)
            else -> cleaned.replace(',', '.')        // 1,5 -> 1.5 (decimal)
        }
        return dotted.replace(Regex("[^0-9.]"), "").toDoubleOrNull()
    }

    private fun formatAmount(value: Double): String =
        if (value % 1.0 == 0.0) value.toLong().toString() else value.toString()

    // Common Russian noun/adjective inflectional endings, longest-first so the longest valid one wins.
    private const val RU_MIN_STEM = 3
    private val RU_ENDINGS = listOf(
        "ами", "ями", "ого", "его", "ому", "ему", "ыми", "ими",
        "ах", "ях", "ов", "ёв", "ев", "ой", "ей", "ою", "ею", "ом", "ем",
        "ый", "ий", "ая", "яя", "ое", "ее", "ые", "ие", "ых", "их", "ым", "им",
        "а", "я", "у", "ю", "о", "е", "и", "ы", "ь", "й", "ё"
    )

    private val NUMBER = Regex("\\d[\\d ,.]*\\d|\\d")
    private val CURRENCY = "[\$€₽£]|usd|eur|rub|gbp|руб(?:\\.|лей|ля)?|долл\\w*|евро"
    private val AMOUNT_BEFORE = Regex("(\\d[\\d ,.]*\\d|\\d)\\s?(?:$CURRENCY)")
    private val AMOUNT_AFTER = Regex("(?:$CURRENCY)\\s?(\\d[\\d ,.]*\\d|\\d)")
}
