package me.obrekht.wishu.invariant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InvariantCheckerTest {

    private fun banned(vararg keywords: String) = Invariant(
        id = "no-alcohol",
        rule = "No alcohol",
        check = CheckType.DETERMINISTIC,
        severity = Severity.HARD,
        refusalReason = "No alcohol",
        detKind = DetKind.BANNED_KEYWORDS,
        keywords = keywords.toList()
    )

    private val budget = Invariant(
        id = "budget",
        rule = "Stay within budget",
        check = CheckType.DETERMINISTIC,
        severity = Severity.HARD,
        refusalReason = "Over budget",
        detKind = DetKind.BUDGET_CEILING
    )

    // ---- BANNED_KEYWORDS -------------------------------------------------------------------------

    @Test fun bannedKeyword_matchesWholeWord_caseAndPunctuationInsensitive() {
        val inv = banned("java")
        assertTrue(InvariantChecker.check("I love Java.", null, listOf(inv)).isNotEmpty())
        assertTrue(InvariantChecker.check("JAVA rocks", null, listOf(inv)).isNotEmpty())
    }

    @Test fun bannedKeyword_doesNotMatchSubstring() {
        // The crucial no-false-positive case: "java" must NOT fire inside "javascript".
        val inv = banned("java")
        assertEquals(emptyList<InvariantResult.Violated>(),
            InvariantChecker.check("I write javascript daily", null, listOf(inv)))
    }

    @Test fun bannedKeyword_multiWordPhrase_matchesContiguousRun() {
        val inv = banned("gift card")
        assertTrue(InvariantChecker.check("maybe a gift card?", null, listOf(inv)).isNotEmpty())
        assertEquals(emptyList<InvariantResult.Violated>(),
            InvariantChecker.check("a card for the gift", null, listOf(inv)))
    }

    @Test fun bannedKeyword_matchesRussianDeclensions() {
        val inv = banned("вино", "водка", "сигареты")
        // The reported miss: "вина" (genitive of "вино") must fire.
        assertTrue(InvariantChecker.check("любителю вина, что-нибудь на вечеринку", null, listOf(inv)).isNotEmpty())
        assertTrue(InvariantChecker.check("бутылку водки", null, listOf(inv)).isNotEmpty())
        assertTrue(InvariantChecker.check("блок сигарет", null, listOf(inv)).isNotEmpty())
    }

    @Test fun bannedKeyword_russianDoesNotOverStem() {
        // "виноград" (grapes) shares a prefix with "вино" but must NOT be flagged.
        val inv = banned("вино")
        assertEquals(emptyList<InvariantResult.Violated>(),
            InvariantChecker.check("корзина винограда", null, listOf(inv)))
    }

    @Test fun bannedKeyword_clean_passes() {
        val inv = banned("wine", "whisky")
        assertEquals(emptyList<InvariantResult.Violated>(),
            InvariantChecker.check("a nice book and a scarf", null, listOf(inv)))
    }

    // ---- BUDGET_CEILING --------------------------------------------------------------------------

    @Test fun budget_overCeiling_flags() {
        val r = InvariantChecker.check("How about a 5000 RUB watch?", "~3000 RUB", listOf(budget))
        assertEquals(1, r.size)
    }

    @Test fun budget_underCeiling_passes() {
        val r = InvariantChecker.check("A 1500 RUB scarf fits.", "~3000 RUB", listOf(budget))
        assertTrue(r.isEmpty())
    }

    @Test fun budget_noBudgetKnown_cannotJudge() {
        val r = InvariantChecker.check("a 9000 RUB gadget", null, listOf(budget))
        assertTrue(r.isEmpty())
    }

    @Test fun budget_rangeUsesUpperBound() {
        // "2000-3000" ceiling = 3000; 2500 is under.
        val r = InvariantChecker.check("a 2500 RUB gift", "2000-3000 RUB", listOf(budget))
        assertTrue(r.isEmpty())
    }

    @Test fun budget_ignoresPlainNumbersWithoutCurrency() {
        // A year / quantity without a currency marker is not a price.
        val r = InvariantChecker.check("released in 2024, set of 5000 pieces", "100 RUB", listOf(budget))
        assertTrue(r.isEmpty())
    }

    // ---- filtering -------------------------------------------------------------------------------

    @Test fun llmOnlyInvariant_isIgnoredByDeterministicChecker() {
        val llmOnly = Invariant(
            id = "soft", rule = "no gift cards",
            check = CheckType.LLM, severity = Severity.SOFT, refusalReason = "impersonal",
            detKind = DetKind.NONE
        )
        assertTrue(InvariantChecker.check("a gift card", "100 RUB", listOf(llmOnly)).isEmpty())
    }
}
