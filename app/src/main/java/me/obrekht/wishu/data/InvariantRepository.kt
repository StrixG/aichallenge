package me.obrekht.wishu.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import me.obrekht.wishu.invariant.CheckType
import me.obrekht.wishu.invariant.DetKind
import me.obrekht.wishu.invariant.Invariant
import me.obrekht.wishu.invariant.Severity
import java.util.UUID

/**
 * Repository for invariants (Day 14). Maps Room entities <-> the [Invariant] domain model so Room types
 * stay out of the agent/UI, exposes the list as a reactive [Flow], and seeds the gift-domain defaults
 * on first run. Persistent storage — distinct from session-scoped chat state.
 */
class InvariantRepository(
    private val dao: InvariantDao,
    private val settings: SettingsRepository
) {

    val invariants: Flow<List<Invariant>> = dao.getAll().map { rows -> rows.map(::toDomain) }

    suspend fun upsert(invariant: Invariant) = dao.upsert(toEntity(invariant))

    suspend fun delete(invariant: Invariant) = dao.deleteById(invariant.id)

    /**
     * Apply the built-in gift-domain invariants. Re-applies (by id, overwriting) whenever [SEED_VERSION]
     * outruns the version last stored — so a changed default (e.g. added keywords) heals stale rows on
     * existing installs, unlike the old seed-only-when-empty rule. Only the `seed-*` ids are touched;
     * user-created invariants (other ids) are never overwritten. Bump [SEED_VERSION] when [DEFAULTS]
     * change. Note: this resets user edits to the seeded rows — acceptable for this learning app.
     */
    suspend fun seedDefaults() {
        if (settings.invariantSeedVersion() >= SEED_VERSION) return
        DEFAULTS.forEach { dao.upsert(toEntity(it)) }
        settings.setInvariantSeedVersion(SEED_VERSION)
    }

    private fun toDomain(e: InvariantEntity): Invariant = Invariant(
        id = e.id,
        rule = e.rule,
        check = enumOf(e.check, CheckType.LLM),
        severity = enumOf(e.severity, Severity.HARD),
        refusalReason = e.refusalReason,
        detKind = enumOf(e.detKind, DetKind.NONE),
        keywords = e.keywords.split('|').map { it.trim() }.filter { it.isNotEmpty() }
    )

    private fun toEntity(i: Invariant): InvariantEntity = InvariantEntity(
        id = i.id.ifBlank { UUID.randomUUID().toString() },
        rule = i.rule,
        check = i.check.name,
        severity = i.severity.name,
        refusalReason = i.refusalReason,
        detKind = i.detKind.name,
        keywords = i.keywords.joinToString("|")
    )

    private inline fun <reified T : Enum<T>> enumOf(name: String, fallback: T): T =
        enumValues<T>().firstOrNull { it.name == name } ?: fallback

    private companion object {
        // Bump whenever DEFAULTS change so existing installs re-seed and stale rows heal.
        const val SEED_VERSION = 1

        val DEFAULTS = listOf(
            Invariant(
                id = "seed-no-alcohol",
                rule = "Never suggest alcohol or tobacco as a gift.",
                check = CheckType.BOTH,
                severity = Severity.HARD,
                refusalReason = "I don't suggest alcohol or tobacco as gifts.",
                detKind = DetKind.BANNED_KEYWORDS,
                keywords = listOf(
                    "alcohol", "wine", "whisky", "whiskey", "beer", "vodka", "cognac",
                    "champagne", "liquor", "tobacco", "cigarettes", "cigars",
                    "алкоголь", "вино", "виски", "пиво", "водка", "коньяк",
                    "шампанское", "табак", "сигареты", "сигары"
                )
            ),
            Invariant(
                id = "seed-budget",
                rule = "Stay within the user's stated budget.",
                check = CheckType.DETERMINISTIC,
                severity = Severity.HARD,
                refusalReason = "That goes over the budget you set.",
                detKind = DetKind.BUDGET_CEILING
            ),
            Invariant(
                id = "seed-no-giftcards",
                rule = "Avoid generic gift cards; prefer a thoughtful, personal gift.",
                check = CheckType.LLM,
                severity = Severity.SOFT,
                refusalReason = "A gift card is impersonal — a thoughtful pick is usually better."
            )
        )
    }
}
