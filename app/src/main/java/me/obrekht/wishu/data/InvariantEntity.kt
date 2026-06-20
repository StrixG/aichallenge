package me.obrekht.wishu.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A stored invariant (Day 14). Persistent — lives in its own table, NOT in the chat transcript, and is
 * never wiped by a session clear (it's a durable rule, like long-term memory). Enums are stored as
 * their `.name`; [keywords] is a single `|`-delimited string (BANNED_KEYWORDS).
 */
@Entity(tableName = "invariants")
data class InvariantEntity(
    @PrimaryKey val id: String,
    val rule: String,
    val check: String,
    val severity: String,
    val refusalReason: String,
    val detKind: String,
    val keywords: String
)
