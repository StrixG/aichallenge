# Day 10 — Context-management strategies

The chat agent (`agent/WishChatAgent`) supports four context-management strategies, switchable live
from the chat top-bar chip (or Settings). All four derive their request payload from one retained
`history` list via a single `when(strategy)` — no class hierarchy.

| Strategy | What goes in the request | Extra call/turn | Persisted state |
|---|---|---|---|
| **Sliding Window** | `system + last N messages + user` | none | per-branch transcript |
| **Summary** | `system + running summary + un-folded tail + user`; folds oldest once the tail outgrows the threshold | flash summary call on fold turns | transcript + `chat_summary` |
| **Sticky Facts** | `system + key-value facts block + last N messages + user`; facts refreshed every turn | flash facts-merge call every turn | transcript + `chat_facts` (JSON) |
| **Branching** | `system + active branch's full transcript + user` | none | transcript + `chat_branches` tree |

`N` = 6 messages (`WINDOW` / `KEEP_RECENT`). Both helper calls use the cheap `deepseek-v4-flash`
model; their token cost is priced at flash rates and shown in the per-turn panel ("helper call").

## How to measure

The per-turn token panel (prompt / cache hit-miss / reply / total / cost + helper-call cost) and the
cumulative session meter are the measurement surface. The `TokenLedger` also logs a running table to
Logcat (tag `TokenLedger`). To compare fairly: run the **same** ~12-message "собираем ТЗ" scenario
(recipient → occasion → budget → likes → dislikes → constraints → refine) once per strategy, then
read the numbers off the panel.

## Comparison (fill in after running on a device)

Run the scenario on each strategy and record the final cumulative tokens/cost and qualitative notes.

| Strategy | Answer quality | Stability (detail retention) | Token cost (Σ) | UX |
|---|---|---|---|---|
| Sliding Window | | drops anything older than the last N messages | flat prompt; cheapest per long turn | simplest; no controls |
| Summary | | keeps a lossy gist of old turns; specifics can blur | bounded prompt + periodic fold cost | "folded" note on fold turns |
| Sticky Facts | | best at retaining named facts (budget, recipient, constraints) | small prompt + a flash call **every** turn | facts note each turn |
| Branching | | full fidelity within a branch; explore alternatives without losing a line | grows with the branch (no trimming) | branch chips + fork action |

### Expected behavior to confirm
- **Sliding Window**: prompt tokens stay roughly flat as the chat grows; the model "forgets" details
  stated more than N messages ago.
- **Summary**: prompt stays bounded; a fold turn shows the helper-call cost; old specifics survive as
  a paraphrase, not verbatim.
- **Sticky Facts**: recipient/budget/constraints are still honored at the end of the run; every turn
  carries a small facts helper-call cost.
- **Branching**: fork at a checkpoint, send different messages down two branches, switch chips — each
  branch keeps its own independent line, and both survive an app restart (persisted tree).
