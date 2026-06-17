package me.obrekht.wishu.agent

import me.obrekht.wishu.network.ChatMessage

/**
 * A read-only snapshot of the agent's three memory layers, returned by
 * [WishChatAgent.memorySnapshot] and rendered by the chat's memory panel.
 *
 *  - [shortTerm]: raw conversation turns; in-memory, session-scoped, auto-updated every turn.
 *  - [working]:   task-specific key-value facts; refreshed every turn; cleared on session reset.
 *  - [longTerm]:  durable user profile; refreshed every turn; survives session reset.
 *
 * The three layers are independent of the [ContextStrategy] — working and long-term are always
 * refreshed and injected; the strategy only decides how the short-term transcript is trimmed.
 */
data class MemorySnapshot(
    val shortTerm: List<ChatMessage>,
    val working: Map<String, String>,
    val longTerm: Map<String, String>
)
