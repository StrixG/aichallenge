package me.obrekht.wishu.agent

/** Tone the user wants replies in. [DEFAULT] = no stated preference (don't constrain the model). */
enum class ReplyStyle {
    DEFAULT, CONCISE, DETAILED, FORMAL, PLAYFUL;

    companion object {
        fun fromName(name: String?): ReplyStyle =
            entries.firstOrNull { it.name == name } ?: DEFAULT
    }
}

/** Shape the user wants replies in. [DEFAULT] = no stated preference. */
enum class ReplyFormat {
    DEFAULT, BULLETS, PROSE;

    companion object {
        fun fromName(name: String?): ReplyFormat =
            entries.firstOrNull { it.name == name } ?: DEFAULT
    }
}

/**
 * The user's *declared* preferences — what they explicitly told the assistant to do (set in
 * Settings). Distinct from the agent's auto-learned long-term memory, which is *inferred*. Both
 * are injected into every request; this one is passed per-call like [ContextStrategy], so the
 * agent stays stateless about it.
 */
data class UserProfile(
    val name: String = "",
    val style: ReplyStyle = ReplyStyle.DEFAULT,
    val format: ReplyFormat = ReplyFormat.DEFAULT,
    val constraints: String = ""
) {
    /** Nothing declared — skip injection entirely so an empty profile costs no tokens. */
    val isEmpty: Boolean
        get() = name.isBlank() &&
            style == ReplyStyle.DEFAULT &&
            format == ReplyFormat.DEFAULT &&
            constraints.isBlank()

    companion object {
        val EMPTY = UserProfile()
    }
}
