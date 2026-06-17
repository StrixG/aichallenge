package me.obrekht.wishu.data

import me.obrekht.wishu.network.ChatMessage

// The agent's persisted summary state: the running summary text and how many of the active branch's
// messages it already folds in.
data class SavedSummary(val summary: String, val summarizedCount: Int)

// A branch of the conversation tree. Carries the tree metadata needed for the version pager.
data class Branch(val id: Long, val label: String, val parentBranchId: Long?, val forkAtCount: Int)

const val ROOT_BRANCH_ID = 1L

// Persists the chat transcript (per branch), the Summary strategy's running summary, the Sticky
// Facts JSON, and the branch tree so context survives app restarts. Maps Room entities to/from the
// agent's network [ChatMessage] so Room types stay out of the agent.
class ChatHistoryRepository(
    private val dao: ChatMessageDao,
    private val summaryDao: ChatSummaryDao,
    private val factsDao: ChatFactsDao,
    private val branchDao: ChatBranchDao,
    private val longTermDao: LongTermMemoryDao
) {

    // The active branch's effective transcript: the parent chain truncated at each fork point, then
    // this branch's own messages. The root branch (no parent) is just its own messages.
    suspend fun transcript(branchId: Long): List<ChatMessage> {
        val branch = branchDao.get(branchId) ?: return emptyList()
        val base = branch.parentBranchId
            ?.let { transcript(it).take(branch.forkAtCount) }
            ?: emptyList()
        val own = dao.getByBranch(branchId).map { ChatMessage(role = it.role, content = it.content) }
        return base + own
    }

    suspend fun append(branchId: Long, role: String, content: String) =
        dao.insert(ChatMessageEntity(role = role, content = content, branchId = branchId))

    suspend fun loadSummary(): SavedSummary? =
        summaryDao.get()?.let { SavedSummary(it.summary, it.summarizedCount) }

    suspend fun saveSummary(summary: String, summarizedCount: Int) =
        summaryDao.upsert(ChatSummaryEntity(summary = summary, summarizedCount = summarizedCount))

    suspend fun loadFacts(): String? = factsDao.get()?.factsJson

    suspend fun saveFacts(factsJson: String) =
        factsDao.upsert(ChatFactsEntity(factsJson = factsJson))

    // Long-term memory — persists across session clears (never touched by clear()) ---------------

    suspend fun loadLongTermMemory(): Map<String, String> =
        longTermDao.getAll().associate { it.key to it.value }

    // Wipe ONLY the persistent long-term profile (Settings "clear memory"). Distinct from clear(),
    // which wipes the session but deliberately leaves long-term intact.
    suspend fun clearLongTermMemory() = longTermDao.clear()

    // Replace-all: clear then re-insert so keys the model dropped (or renamed/typo'd) vanish from
    // the DB. This is the persistence path, NOT the session clear() — long-term still survives that.
    suspend fun saveAllLongTermFacts(facts: Map<String, String>) {
        longTermDao.clear()
        facts.forEach { (key, value) ->
            longTermDao.upsert(LongTermMemoryEntity(key = key, value = value))
        }
    }

    // Branch tree ---------------------------------------------------------------------------------

    // All branches, guaranteeing the root exists (created lazily on first access / fresh install).
    suspend fun branches(): List<Branch> {
        ensureRoot()
        return branchDao.getAll().map { Branch(it.id, it.label, it.parentBranchId, it.forkAtCount) }
    }

    // Fork a new branch off [parentId] at [forkAtCount] messages — the checkpoint. Returns its id.
    suspend fun createBranch(parentId: Long, forkAtCount: Int, label: String): Long =
        branchDao.insert(
            ChatBranchEntity(label = label, parentBranchId = parentId, forkAtCount = forkAtCount)
        )

    private suspend fun ensureRoot() {
        if (branchDao.get(ROOT_BRANCH_ID) == null) {
            branchDao.insert(ChatBranchEntity(id = ROOT_BRANCH_ID, label = "main"))
        }
    }

    // Wipe the whole session back to an empty root branch with no summary/facts.
    suspend fun clear() {
        dao.clear()
        summaryDao.clear()
        factsDao.clear()
        branchDao.clearForks()
        ensureRoot()
    }
}
