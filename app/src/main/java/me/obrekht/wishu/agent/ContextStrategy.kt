package me.obrekht.wishu.agent

/**
 * How the agent manages conversation context when building each request. Selected from the chat
 * (a chip) or Settings; the agent branches on it in a single `when` — no class hierarchy.
 *
 * - [SUMMARY]: keep a running prose summary of folded-away turns + the recent raw tail.
 * - [SLIDING_WINDOW]: send only the last N messages, drop everything older. No extra call.
 * - [STICKY_FACTS]: keep a key-value facts block (recipient, budget, likes…) refreshed each turn,
 *   sent alongside the last N messages.
 * - [BRANCHING]: send the active branch's full transcript; context is managed by *which* branch
 *   you're on, not by trimming.
 */
enum class ContextStrategy {
    SUMMARY,
    SLIDING_WINDOW,
    STICKY_FACTS,
    BRANCHING;

    companion object {
        val DEFAULT = SLIDING_WINDOW

        fun fromName(name: String?): ContextStrategy =
            entries.firstOrNull { it.name == name } ?: DEFAULT
    }
}
